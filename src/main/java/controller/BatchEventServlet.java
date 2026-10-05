package controller;

import blockchain.BlockValidationException;
import blockchain.TransactionValidationException;
import blockchain.TransactionCodec;
import com.google.gson.JsonArray;
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
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.EventType;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import network.ShipmentProposalWireCodec;
import network.PeerDeliveryException;
import security.ApiJson;
import security.SessionAttributes;
import service.AuthenticatedAccount;
import service.AuthenticationException;
import service.BatchService;
import service.NodeRuntime;
import service.TraceabilityService;
import service.ShipmentProposalService;

@WebServlet("/api/v1/batches/*")
public final class BatchEventServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 64 * 1024;
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> EVENT_FIELDS = Set.of(
            "eventId", "batchCode", "eventType", "eventTime", "data", "signatures");
    private static final Set<String> SIGNATURE_FIELDS = Set.of(
            "organizationId", "keyId", "purpose", "signature");

    private transient BatchService batchService;
    private transient TraceabilityService traceabilityService;
    private transient ShipmentProposalService shipmentProposalService;
    private transient String networkId;

    public BatchEventServlet() {
    }

    BatchEventServlet(BatchService batchService) {
        this.batchService = batchService;
        this.networkId = "test-network";
    }

    BatchEventServlet(BatchService batchService, TraceabilityService traceabilityService) {
        this.batchService = batchService;
        this.traceabilityService = traceabilityService;
        this.networkId = "test-network";
    }

    BatchEventServlet(
            BatchService batchService,
            TraceabilityService traceabilityService,
            ShipmentProposalService shipmentProposalService
    ) {
        this.batchService = batchService;
        this.traceabilityService = traceabilityService;
        this.shipmentProposalService = shipmentProposalService;
        this.networkId = "test-network";
    }

    @Override
    public void init() throws ServletException {
        if (batchService != null && traceabilityService != null && shipmentProposalService != null) {
            return;
        }
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace batch runtime is not initialized");
        }
        batchService = nodeRuntime.batchService();
        traceabilityService = nodeRuntime.traceabilityService();
        shipmentProposalService = nodeRuntime.shipmentProposalService();
        networkId = nodeRuntime.peerLedgerService().networkId();
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String pathInfo = request.getPathInfo();
        if (pathInfo == null || pathInfo.length() < 2
                || pathInfo.charAt(0) != '/' || pathInfo.indexOf('/', 1) >= 0) {
            notFound(response);
            return;
        }
        try {
            var view = traceabilityService.findBatch(
                    pathInfo.substring(1), currentAccount(request));
            if (view.isEmpty()) {
                notFound(response);
                return;
            }
            ApiJson.write(response, HttpServletResponse.SC_OK,
                    TraceabilityJson.batch(view.orElseThrow()));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (RuntimeException exception) {
            getServletContext().log("Canonical batch history could not be verified", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Canonical batch history is unavailable",
                            "CHAIN_VERIFICATION_FAILED"));
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
            String pathInfo = request.getPathInfo();
            if (isShipmentProposalPath(pathInfo)) {
                String requestBody = readBody(request);
                JsonObject proposalBody = ApiJson.parseObject(requestBody);
                JsonElement bodyBatchCode = proposalBody.get("batchCode");
                if (bodyBatchCode != null && bodyBatchCode.isJsonPrimitive()
                        && bodyBatchCode.getAsJsonPrimitive().isString()
                        && !pathInfo.split("/")[1].equals(bodyBatchCode.getAsString())) {
                    throw new IllegalArgumentException(
                            "Path batchCode must match the proposal batchCode");
                }
                ShipmentProposal proposal = ShipmentProposalWireCodec.decode(requestBody);
                ShipmentProposal created = shipmentProposalService.create(
                        currentAccount(request), proposal);
                ApiJson.write(response, HttpServletResponse.SC_ACCEPTED,
                        ShipmentProposalJson.success(
                                "Shipment proposal accepted",
                                ShipmentProposalJson.proposal(
                                        created, shipmentProposalService.payloadHash(created))));
                return;
            }
            boolean harvestRequest = pathInfo == null || "/".equals(pathInfo);
            String pathBatchCode = harvestRequest ? null : batchCodeFromPath(pathInfo);
            if (!harvestRequest && pathBatchCode == null) {
                notFound(response);
                return;
            }
            JsonObject body = ApiJson.parseObject(readBody(request));
            rejectUnknownFields(body, EVENT_FIELDS, "event");
            BatchEvent event = eventFrom(body);
            if (harvestRequest && event.eventType() != EventType.HARVESTED) {
                throw new IllegalArgumentException("POST /batches accepts only HARVESTED events");
            }
            if (!harvestRequest && !pathBatchCode.equals(event.batchCode())) {
                throw new IllegalArgumentException("Path batchCode must match the signed event batchCode");
            }
            var result = batchService.submit(currentAccount(request), event);

            JsonObject data = new JsonObject();
            data.addProperty("transactionId", event.transactionId());
            data.addProperty("status", "PENDING");
            JsonObject envelope = new JsonObject();
            envelope.addProperty("success", true);
            envelope.addProperty("message", result == dal.TransactionDAO.SubmissionResult.INSERTED
                    ? "Batch event accepted"
                    : "Batch event is already pending");
            envelope.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_ACCEPTED, envelope);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
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
            getServletContext().log("Batch event persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Transaction service is unavailable", "TRANSACTION_SERVICE_UNAVAILABLE"));
        } catch (PeerDeliveryException exception) {
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error(exception.getMessage(), "SHIPMENT_PEER_UNAVAILABLE"));
        }
    }

    private BatchEvent eventFrom(JsonObject body) {
        String eventId = requiredString(body, "eventId");
        String batchCode = requiredString(body, "batchCode");
        EventType eventType;
        try {
            eventType = EventType.valueOf(requiredString(body, "eventType"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("eventType is not supported", exception);
        }

        Instant eventTime = eventTime(requiredString(body, "eventTime"));
        JsonElement dataElement = body.get("data");
        if (dataElement == null || !dataElement.isJsonObject()) {
            throw new IllegalArgumentException("data must be a JSON object");
        }
        JsonElement signaturesElement = body.get("signatures");
        if (signaturesElement == null || !signaturesElement.isJsonArray()
                || signaturesElement.getAsJsonArray().isEmpty()) {
            throw new IllegalArgumentException("signatures must be a non-empty JSON array");
        }

        List<SignatureEnvelope> signatures = new ArrayList<>();
        for (JsonElement signatureElement : signaturesElement.getAsJsonArray()) {
            if (!signatureElement.isJsonObject()) {
                throw new IllegalArgumentException("Each signature must be a JSON object");
            }
            JsonObject signature = signatureElement.getAsJsonObject();
            rejectUnknownFields(signature, SIGNATURE_FIELDS, "signature");
            signatures.add(new SignatureEnvelope(
                    requiredString(signature, "organizationId"),
                    requiredString(signature, "keyId"),
                    requiredString(signature, "purpose"),
                    requiredString(signature, "signature")));
        }

        Map<String, Object> data = new LinkedHashMap<>();
        dataElement.getAsJsonObject().entrySet().forEach(entry ->
                data.put(entry.getKey(), jsonValue(entry.getValue())));
        BatchEvent unsignedId = new BatchEvent(
                "pending", eventId, batchCode, eventType, eventTime, data, signatures);
        String transactionId = TransactionCodec.transactionId(networkId, unsignedId);
        return new BatchEvent(
                transactionId, eventId, batchCode, eventType, eventTime, data, signatures);
    }

    private boolean isShipmentProposalPath(String pathInfo) {
        if (pathInfo == null) {
            return false;
        }
        String[] segments = pathInfo.split("/");
        return segments.length == 3
                && segments[0].isEmpty()
                && !segments[1].isBlank()
                && "shipments".equals(segments[2]);
    }

    private Object jsonValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> object = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet().forEach(entry ->
                    object.put(entry.getKey(), jsonValue(entry.getValue())));
            return object;
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            List<Object> values = new ArrayList<>(array.size());
            array.forEach(item -> values.add(jsonValue(item)));
            return values;
        }
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsBigDecimal();
        }
        return element.getAsString();
    }

    private String batchCodeFromPath(String pathInfo) {
        String[] segments = pathInfo.split("/");
        if (segments.length != 3 || !segments[0].isEmpty()
                || segments[1].isBlank() || !"events".equals(segments[2])) {
            return null;
        }
        return segments[1];
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

    private String requiredString(JsonObject body, String property) {
        JsonElement element = body.get(property);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(property + " must be a non-blank string");
        }
        return element.getAsString();
    }

    private Instant eventTime(String value) {
        try {
            return Instant.from(EVENT_TIME_FORMATTER.parse(value));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "eventTime must be a UTC timestamp with exactly three fractional digits", exception);
        }
    }

    private void rejectUnknownFields(JsonObject object, Set<String> allowed, String fieldName) {
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(fieldName + " contains unsupported fields");
        }
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("Batch was not found", "BATCH_NOT_FOUND"));
    }
}
