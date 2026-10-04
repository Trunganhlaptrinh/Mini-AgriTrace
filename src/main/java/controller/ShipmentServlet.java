package controller;

import blockchain.BlockValidationException;
import blockchain.TransactionValidationException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dal.DuplicateTransactionException;
import dal.PersistenceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import network.PeerDeliveryException;
import security.ApiJson;
import security.SessionAttributes;
import service.AuthenticatedAccount;
import service.AuthenticationException;
import service.NodeRuntime;
import service.ShipmentProposalNotFoundException;
import service.ShipmentProposalService;

@WebServlet("/api/v1/shipments/*")
public final class ShipmentServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private transient ShipmentProposalService shipmentProposalService;

    public ShipmentServlet() {
    }

    ShipmentServlet(ShipmentProposalService shipmentProposalService) {
        this.shipmentProposalService = shipmentProposalService;
    }

    @Override
    public void init() throws ServletException {
        if (shipmentProposalService != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace shipment runtime is not initialized");
        }
        shipmentProposalService = nodeRuntime.shipmentProposalService();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        try {
            AuthenticatedAccount actor = currentAccount(request);
            String path = request.getPathInfo();
            if ("/inbox".equals(path)) {
                List<ShipmentProposal> proposals = shipmentProposalService.inbox(actor);
                com.google.gson.JsonArray values = new com.google.gson.JsonArray();
                proposals.forEach(proposal -> values.add(ShipmentProposalJson.proposal(
                        proposal, shipmentProposalService.payloadHash(proposal))));
                JsonObject data = new JsonObject();
                data.add("proposals", values);
                ApiJson.write(response, HttpServletResponse.SC_OK,
                        ShipmentProposalJson.success("Shipment inbox", data));
                return;
            }
            String proposalId = proposalId(path);
            if (proposalId == null) {
                notFound(response);
                return;
            }
            var result = shipmentProposalService.find(actor, proposalId);
            if (result.isEmpty()) {
                notFound(response);
                return;
            }
            ShipmentProposal proposal = result.orElseThrow();
            ApiJson.write(response, HttpServletResponse.SC_OK,
                    ShipmentProposalJson.success(
                            "Shipment proposal",
                            ShipmentProposalJson.proposal(
                                    proposal, shipmentProposalService.payloadHash(proposal))));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (ShipmentProposalNotFoundException exception) {
            notFound(response);
        } catch (PersistenceException exception) {
            getServletContext().log("Shipment proposal read failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Shipment proposal service is unavailable",
                            "SHIPMENT_SERVICE_UNAVAILABLE"));
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
            String path = request.getPathInfo();
            String proposalId = endorsementProposalId(path);
            if (proposalId == null) {
                notFound(response);
                return;
            }
            JsonObject body = ApiJson.parseObject(readBody(request));
            rejectUnknownFields(body, Set.of("keyId", "purpose", "value"), "carrier signature");
            AuthenticatedAccount actor = currentAccount(request);
            if (actor.organizationId() == null) {
                throw new AuthenticationException(
                        "FORBIDDEN", "An organization account is required", 403);
            }
            SignatureEnvelope signature = new SignatureEnvelope(
                    actor.organizationId(),
                    requiredString(body, "keyId"),
                    requiredString(body, "purpose"),
                    requiredString(body, "value"));
            ShipmentProposal proposal = shipmentProposalService.endorse(
                    actor, proposalId, signature);
            JsonObject data = new JsonObject();
            data.addProperty("proposalId", proposalId);
            data.addProperty("transactionId", proposal.submittedTransactionId());
            data.addProperty("status", "PENDING");
            ApiJson.write(response, HttpServletResponse.SC_ACCEPTED,
                    ShipmentProposalJson.success("Shipment submitted", data));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (ShipmentProposalNotFoundException exception) {
            notFound(response);
        } catch (TransactionValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (BlockValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (DuplicateTransactionException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (IllegalStateException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), "SHIPMENT_PROPOSAL_CONFLICT"));
        } catch (PersistenceException exception) {
            getServletContext().log("Shipment proposal persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Shipment proposal service is unavailable",
                            "SHIPMENT_SERVICE_UNAVAILABLE"));
        } catch (PeerDeliveryException exception) {
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error(exception.getMessage(), "SHIPMENT_PEER_UNAVAILABLE"));
        }
    }

    private AuthenticatedAccount currentAccount(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(SessionAttributes.USER_ID) instanceof Long userId)
                || !(session.getAttribute(SessionAttributes.USERNAME) instanceof String username)
                || !(session.getAttribute(SessionAttributes.ROLE) instanceof String role)) {
            throw new AuthenticationException(
                    "UNAUTHENTICATED", "Authentication is required", 401);
        }
        Object organizationId = session.getAttribute(SessionAttributes.ORGANIZATION_ID);
        return new AuthenticatedAccount(
                userId, username, role, organizationId instanceof String value ? value : null);
    }

    private String proposalId(String path) {
        if (path == null || path.length() < 2 || path.charAt(0) != '/'
                || path.indexOf('/', 1) >= 0 || "/inbox".equals(path)) {
            return null;
        }
        return path.substring(1);
    }

    private String endorsementProposalId(String path) {
        if (path == null) {
            return null;
        }
        String[] segments = path.split("/");
        return segments.length == 3 && segments[0].isEmpty()
                && !segments[1].isBlank() && "endorsements".equals(segments[2])
                ? segments[1] : null;
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

    private String requiredString(JsonObject body, String field) {
        JsonElement element = body.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return element.getAsString();
    }

    private void rejectUnknownFields(JsonObject object, Set<String> allowed, String name) {
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(name + " contains unsupported fields");
        }
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("Shipment proposal was not found", "SHIPMENT_PROPOSAL_NOT_FOUND"));
    }
}
