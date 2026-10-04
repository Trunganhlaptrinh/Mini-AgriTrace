package controller;

import com.google.gson.JsonObject;
import blockchain.BlockValidationException;
import blockchain.TransactionValidationException;
import dal.DuplicateTransactionException;
import dal.PersistenceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.UUID;
import model.PeerRegistration;
import model.ShipmentProposal;
import network.ShipmentEndorsementWireCodec;
import network.PeerAuthenticationException;
import network.ShipmentProposalWireCodec;
import security.ApiJson;
import security.PeerAuthenticationFilter;
import service.AuthenticationException;
import service.NodeRuntime;
import service.ShipmentProposalNotFoundException;
import service.ShipmentProposalService;

@WebServlet("/api/v1/internal/p2p/shipment-proposals/*")
public final class PeerShipmentProposalServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private transient ShipmentProposalService shipmentProposalService;

    public PeerShipmentProposalServlet() {
    }

    PeerShipmentProposalServlet(ShipmentProposalService shipmentProposalService) {
        this.shipmentProposalService = shipmentProposalService;
    }

    @Override
    public void init() throws ServletException {
        if (shipmentProposalService != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace peer runtime is not initialized");
        }
        shipmentProposalService = nodeRuntime.shipmentProposalService();
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
            Object peerAttribute = request.getAttribute(
                    PeerAuthenticationFilter.AUTHENTICATED_PEER_ATTRIBUTE);
            if (!(peerAttribute instanceof PeerRegistration sourcePeer)) {
                throw new PeerAuthenticationException("Authenticated peer identity is missing");
            }
            NodeRuntime runtime = (NodeRuntime) getServletContext()
                    .getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
            String pathInfo = request.getPathInfo();
            if (pathInfo == null || pathInfo.isEmpty() || "/".equals(pathInfo)) {
                receiveProposal(request, response, sourcePeer, runtime);
            } else {
                receiveEndorsement(request, response, sourcePeer, runtime, pathInfo);
            }
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException | PeerAuthenticationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_FORBIDDEN,
                    ApiJson.error(exception.getMessage(), "PEER_NOT_AUTHORIZED"));
        } catch (DuplicateTransactionException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (ShipmentProposalNotFoundException exception) {
            ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiJson.error("Shipment proposal was not found", "SHIPMENT_PROPOSAL_NOT_FOUND"));
        } catch (TransactionValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (BlockValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Relayed shipment message persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Shipment proposal storage is unavailable",
                            "SHIPMENT_STORAGE_UNAVAILABLE"));
        }
    }

    private void receiveProposal(
            HttpServletRequest request,
            HttpServletResponse response,
            PeerRegistration sourcePeer,
            NodeRuntime runtime
    ) throws IOException {
        ShipmentProposal proposal = ShipmentProposalWireCodec.decode(readBody(request));
        var received = shipmentProposalService.receiveFromPeer(
                sourcePeer, runtime.peerIdentity().registration(), proposal);
        JsonObject data = new JsonObject();
        data.addProperty("proposalId", received.proposal().proposalId());
        data.addProperty("status", received.proposal().status().name());
        data.addProperty("payloadHash", shipmentProposalService.payloadHash(received.proposal()));
        ApiJson.write(response,
                received.inserted() ? HttpServletResponse.SC_ACCEPTED : HttpServletResponse.SC_OK,
                ShipmentProposalJson.success(
                        received.inserted()
                                ? "Shipment proposal received"
                                : "Shipment proposal was already received",
                        data));
    }

    private void receiveEndorsement(
            HttpServletRequest request,
            HttpServletResponse response,
            PeerRegistration sourcePeer,
            NodeRuntime runtime,
            String pathInfo
    ) throws IOException {
        String pathProposalId = proposalIdFromPath(pathInfo);
        if (pathProposalId == null) {
            ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiJson.error("P2P shipment endpoint was not found", "NOT_FOUND"));
            return;
        }
        ShipmentEndorsementWireCodec.Endorsement endorsement =
                ShipmentEndorsementWireCodec.decode(readBody(request));
        if (!pathProposalId.equals(endorsement.proposalId())) {
            throw new IllegalArgumentException("Path proposalId must match the endorsement proposalId");
        }
        ShipmentProposal received = shipmentProposalService.receiveEndorsementFromPeer(
                sourcePeer,
                runtime.peerIdentity().registration(),
                pathProposalId,
                endorsement.event());
        JsonObject data = new JsonObject();
        data.addProperty("proposalId", received.proposalId());
        data.addProperty("transactionId", received.submittedTransactionId());
        data.addProperty("status", received.status().name());
        ApiJson.write(response, HttpServletResponse.SC_ACCEPTED,
                ShipmentProposalJson.success(
                        received.status() == ShipmentProposal.Status.SUBMITTED
                                ? "Shipment endorsement received"
                                : "Shipment endorsement was already received",
                        data));
    }

    private String proposalIdFromPath(String pathInfo) {
        String[] segments = pathInfo.split("/", -1);
        if (segments.length != 3 || !segments[0].isEmpty()
                || !"endorsements".equals(segments[2])) {
            return null;
        }
        try {
            String proposalId = UUID.fromString(segments[1]).toString();
            return proposalId.equalsIgnoreCase(segments[1]) ? proposalId : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the size limit");
        }
        request.setCharacterEncoding("UTF-8");
        BufferedReader reader = request.getReader();
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[2048];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (body.length() + count > MAX_REQUEST_CHARS) {
                throw new IllegalArgumentException("Request body exceeds the size limit");
            }
            body.append(buffer, 0, count);
        }
        return body.toString();
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }
}
