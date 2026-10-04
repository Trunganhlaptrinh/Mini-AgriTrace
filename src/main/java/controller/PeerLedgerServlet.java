package controller;

import blockchain.BlockProcessingResult;
import blockchain.BlockRepository;
import blockchain.BlockValidationException;
import blockchain.TransactionValidationException;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dal.DuplicateTransactionException;
import dal.PersistenceException;
import dal.TransactionDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Comparator;
import java.util.List;
import model.LedgerTransaction;
import model.PeerRegistration;
import network.LedgerBlockWireCodec;
import network.LedgerTransactionWireCodec;
import network.PeerAuthenticationException;
import security.ApiJson;
import security.PeerAuthenticationFilter;
import service.AuthenticationException;
import service.NodeRuntime;
import service.PeerLedgerService;

@WebServlet("/api/v1/internal/p2p/*")
public final class PeerLedgerServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024 * 1024;
    private static final int PENDING_PAGE_SIZE = 100;
    private transient PeerLedgerService peerLedgerService;

    public PeerLedgerServlet() {
    }

    PeerLedgerServlet(PeerLedgerService peerLedgerService) {
        this.peerLedgerService = peerLedgerService;
    }

    @Override
    public void init() throws ServletException {
        if (peerLedgerService != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace peer runtime is not initialized");
        }
        peerLedgerService = nodeRuntime.peerLedgerService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            requireAuthenticatedPeer(request);
            String pathInfo = request.getPathInfo();
            if ("/chain/locator".equals(pathInfo)) {
                writeLocator(response);
            } else if ("/transactions/pending".equals(pathInfo)) {
                writePendingTransactions(request, response);
            } else if ("/blocks/next".equals(pathInfo)) {
                writeNextBlock(request, response);
            } else if (pathInfo != null && pathInfo.startsWith("/blocks/")) {
                writeBlock(response, pathInfo.substring("/blocks/".length()));
            } else {
                notFound(response);
            }
        } catch (PeerAuthenticationException | AuthenticationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error(exception.getMessage(), "PEER_NOT_AUTHORIZED"));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (PersistenceException exception) {
            getServletContext().log("Peer ledger read failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Peer ledger storage is unavailable", "PEER_LEDGER_UNAVAILABLE"));
        } catch (RuntimeException exception) {
            getServletContext().log("Peer ledger state could not be verified", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Canonical peer ledger is unavailable", "PEER_LEDGER_UNAVAILABLE"));
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isJson(request.getContentType())) {
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }
        try {
            PeerRegistration sourcePeer = requireAuthenticatedPeer(request);
            NodeRuntime runtime = (NodeRuntime) getServletContext()
                    .getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
            String pathInfo = request.getPathInfo();
            if ("/transactions".equals(pathInfo)) {
                receiveTransaction(request, response, sourcePeer, runtime);
            } else if ("/blocks".equals(pathInfo)) {
                receiveBlock(request, response, sourcePeer, runtime);
            } else {
                notFound(response);
            }
        } catch (PeerAuthenticationException | AuthenticationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error(exception.getMessage(), "PEER_NOT_AUTHORIZED"));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (TransactionValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (BlockValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (DuplicateTransactionException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Peer ledger write failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Peer ledger storage is unavailable", "PEER_LEDGER_UNAVAILABLE"));
        } catch (RuntimeException exception) {
            getServletContext().log("Peer ledger transaction could not be accepted", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Peer ledger service is unavailable", "PEER_LEDGER_UNAVAILABLE"));
        }
    }

    private void writeLocator(HttpServletResponse response) throws IOException {
        JsonArray entries = new JsonArray();
        peerLedgerService.locator().forEach(entry -> {
            JsonObject item = new JsonObject();
            item.addProperty("height", entry.height());
            item.addProperty("hash", entry.hash());
            entries.add(item);
        });
        JsonObject data = new JsonObject();
        data.add("blocks", entries);
        success(response, HttpServletResponse.SC_OK, "Canonical chain locator", data);
    }

    private void writePendingTransactions(
            HttpServletRequest request,
            HttpServletResponse response
    ) throws IOException {
        String after = request.getParameter("after");
        if (after != null && !after.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("after must be a lowercase transaction ID");
        }
        List<LedgerTransaction> candidates = peerLedgerService.pendingTransactions().stream()
                .sorted(Comparator.comparing(LedgerTransaction::transactionId))
                .filter(transaction -> after == null || transaction.transactionId().compareTo(after) > 0)
                .limit(PENDING_PAGE_SIZE + 1L)
                .toList();
        boolean hasMore = candidates.size() > PENDING_PAGE_SIZE;
        List<LedgerTransaction> page = hasMore
                ? candidates.subList(0, PENDING_PAGE_SIZE)
                : candidates;
        JsonArray items = new JsonArray();
        page.forEach(transaction -> items.add(
                LedgerTransactionWireCodec.encode(peerLedgerService.networkId(), transaction)));
        JsonObject data = new JsonObject();
        data.add("transactions", items);
        data.addProperty("nextAfter",
                hasMore ? page.get(page.size() - 1).transactionId() : "");
        success(response, HttpServletResponse.SC_OK, "Pending transactions", data);
    }

    private void writeBlock(HttpServletResponse response, String hash) throws IOException {
        if (hash.contains("/")) {
            notFound(response);
            return;
        }
        BlockRepository.StoredBlock storedBlock = peerLedgerService.findCanonicalBlock(hash);
        if (storedBlock == null) {
            notFound(response);
            return;
        }
        JsonObject data = LedgerBlockWireCodec.encode(peerLedgerService.networkId(), storedBlock);
        success(response, HttpServletResponse.SC_OK, "Canonical block", data);
    }

    private void writeNextBlock(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String afterHash = request.getParameter("afterHash");
        BlockRepository.StoredBlock nextBlock = peerLedgerService.nextCanonicalBlock(afterHash);
        if (nextBlock == null) {
            notFound(response);
            return;
        }
        JsonObject data = LedgerBlockWireCodec.encode(peerLedgerService.networkId(), nextBlock);
        success(response, HttpServletResponse.SC_OK, "Next canonical block", data);
    }

    private void receiveTransaction(
            HttpServletRequest request,
            HttpServletResponse response,
            PeerRegistration sourcePeer,
            NodeRuntime runtime
    ) throws IOException {
        LedgerTransaction transaction = LedgerTransactionWireCodec.decode(
                peerLedgerService.networkId(), security.ApiJson.parseObject(readBody(request)));
        TransactionDAO.SubmissionResult result = peerLedgerService.receiveTransaction(
                sourcePeer,
                runtime.peerIdentity().registration(),
                transaction);
        JsonObject data = new JsonObject();
        data.addProperty("transactionId", transaction.transactionId());
        data.addProperty("status", "PENDING");
        success(
                response,
                HttpServletResponse.SC_ACCEPTED,
                result == TransactionDAO.SubmissionResult.INSERTED
                        ? "Peer transaction accepted" : "Peer transaction already known",
                data);
    }

    private void receiveBlock(
            HttpServletRequest request,
            HttpServletResponse response,
            PeerRegistration sourcePeer,
            NodeRuntime runtime
    ) throws IOException {
        BlockRepository.StoredBlock block = LedgerBlockWireCodec.decode(
                peerLedgerService.networkId(), readBody(request));
        BlockProcessingResult result = peerLedgerService.receiveBlock(
                sourcePeer,
                runtime.peerIdentity().registration(),
                block);
        JsonObject data = new JsonObject();
        data.addProperty("blockHash", result.validation().block().hash());
        data.addProperty("height", result.validation().block().header().height());
        data.addProperty("persistenceResult", result.persistenceResult().name());
        data.addProperty("canonicalTipHash",
                result.canonicalState().tip() == null ? "" : result.canonicalState().tip().hash());
        success(response, HttpServletResponse.SC_ACCEPTED, "Peer block validated and stored", data);
    }

    private PeerRegistration requireAuthenticatedPeer(HttpServletRequest request) {
        Object attribute = request.getAttribute(PeerAuthenticationFilter.AUTHENTICATED_PEER_ATTRIBUTE);
        if (!(attribute instanceof PeerRegistration peer)) {
            throw new PeerAuthenticationException("Authenticated peer identity is missing");
        }
        return peer;
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the size limit");
        }
        request.setCharacterEncoding("UTF-8");
        BufferedReader reader = request.getReader();
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[4096];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (body.length() + count > MAX_REQUEST_CHARS) {
                throw new IllegalArgumentException("Request body exceeds the size limit");
            }
            body.append(buffer, 0, count);
        }
        return body.toString();
    }

    private void success(
            HttpServletResponse response,
            int status,
            String message,
            JsonObject data
    ) throws IOException {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("success", true);
        envelope.addProperty("message", message);
        envelope.add("data", data);
        ApiJson.write(response, status, envelope);
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("P2P ledger endpoint was not found", "NOT_FOUND"));
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }
}
