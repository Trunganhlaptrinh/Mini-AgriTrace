package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import blockchain.BlockRepository;
import blockchain.TransactionCodec;
import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.GovernanceRegistry;
import dal.TransactionDAO;
import model.BatchEvent;
import org.junit.jupiter.api.Test;
import security.SessionAttributes;
import service.BatchService;
import service.TraceabilityService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BatchEventServletTest {
    @Test
    void acceptsHarvestAndReturnsPendingTransactionResponse() throws Exception {
        AtomicReference<BatchEvent> submitted = new AtomicReference<>();
        BatchService service = new BatchService(event -> {
            submitted.set(event);
            return TransactionDAO.SubmissionResult.INSERTED;
        });
        FakeResponse response = new FakeResponse();
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 7L);
        session.attributes.put(SessionAttributes.USERNAME, "farmer.one");
        session.attributes.put(SessionAttributes.ROLE, "FARMER");
        session.attributes.put(SessionAttributes.ORGANIZATION_ID, "farm-1");

        new BatchEventServlet(service).doPost(
                request("/", harvestBody(), session.proxy()),
                response.proxy());

        assertEquals(202, response.status);
        assertTrue(response.body().contains("\"status\":\"PENDING\""));
        assertEquals("farm-1", submitted.get().signatures().get(0).organizationId());
        assertEquals(Instant.parse("2026-10-04T10:00:00.000Z"), submitted.get().eventTime());
        assertEquals(TransactionCodec.transactionId("test-network", submitted.get()),
                submitted.get().transactionId());
    }

    @Test
    void rejectsAPathCodeThatDiffersFromTheSignedEventCode() throws Exception {
        FakeResponse response = new FakeResponse();

        new BatchEventServlet(new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED))
                .doPost(request("/MANGO-OTHER/events", harvestBody(), authenticatedSession().proxy()),
                        response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("Path batchCode must match"));
    }

    @Test
    void harvestEndpointRejectsOtherEventTypes() throws Exception {
        FakeResponse response = new FakeResponse();
        String body = harvestBody().replace("\"HARVESTED\"", "\"PACKAGED\"");

        new BatchEventServlet(new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED))
                .doPost(request("/", body, authenticatedSession().proxy()), response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("accepts only HARVESTED"));
    }

    @Test
    void rejectsUnsupportedEventEnvelopeFields() throws Exception {
        FakeResponse response = new FakeResponse();
        String body = harvestBody().replace("\"eventId\"", "\"unexpected\":true,\"eventId\"");

        new BatchEventServlet(new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED))
                .doPost(request("/", body, authenticatedSession().proxy()), response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("event contains unsupported fields"));
    }

    @Test
    void rejectsRequestsWithoutAnAuthenticatedSession() throws Exception {
        FakeResponse response = new FakeResponse();

        new BatchEventServlet(new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED))
                .doPost(request("/", harvestBody(), null), response.proxy());

        assertEquals(401, response.status);
        assertTrue(response.body().contains("UNAUTHENTICATED"));
    }

    @Test
    void shipmentProposalRouteRequiresPathAndPayloadBatchCodesToMatch() throws Exception {
        FakeResponse response = new FakeResponse();

        new BatchEventServlet(new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED),
                null, null).doPost(
                        request("/MANGO-1/shipments", "{\"batchCode\":\"MANGO-2\"}",
                                authenticatedSession().proxy()),
                        response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("Path batchCode must match"));
    }

    @Test
    void authenticatedBatchReadReturnsNotFoundForUnknownCanonicalBatch() throws Exception {
        FakeResponse response = new FakeResponse();

        new BatchEventServlet(
                new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED),
                emptyTraceabilityService()).doGet(
                        request("/UNKNOWN-BATCH", "", authenticatedSession().proxy()),
                        response.proxy());

        assertEquals(404, response.status);
        assertTrue(response.body().contains("BATCH_NOT_FOUND"));
    }

    @Test
    void publicTraceReadReturnsNotFoundForUnknownCanonicalBatch() throws Exception {
        FakeResponse response = new FakeResponse();

        new PublicTraceServlet(emptyTraceabilityService()).doGet(
                request("/UNKNOWN-BATCH/trace", "", null),
                response.proxy());

        assertEquals(404, response.status);
        assertTrue(response.body().contains("BATCH_NOT_FOUND"));
    }

    private TraceabilityService emptyTraceabilityService() {
        BlockRepository repository = new BlockRepository() {
            @Override
            public StoreResult storeValidatedBlock(BlockValidationResult validation) {
                return StoreResult.ALREADY_PRESENT;
            }

            @Override
            public java.util.List<StoredBlock> loadCanonicalChain() {
                return java.util.List.of();
            }

            @Override
            public java.util.List<StoredBlock> loadBranch(String tipHash) {
                return java.util.List.of();
            }
        };
        Blockchain blockchain = new Blockchain(
                new BlockValidator("test-network", 1, "0".repeat(64)),
                repository,
                BlockValidationContext.genesis(GovernanceRegistry.empty()));
        return new TraceabilityService(blockchain);
    }

    private static String harvestBody() {
        return """
                {
                  "eventId": "event-1",
                  "batchCode": "MANGO-1",
                  "eventType": "HARVESTED",
                  "eventTime": "2026-10-04T10:00:00.000Z",
                  "data": {
                    "productType": "Mango",
                    "variety": "Cat Hoa Loc",
                    "harvestDate": "2026-10-04",
                    "quantity": "1.000",
                    "quantityUnit": "kg",
                    "farmName": "Farm One",
                    "province": "Tien Giang"
                  },
                  "signatures": [{
                    "organizationId": "farm-1",
                    "keyId": "farm-key-1",
                    "purpose": "FARMER_HARVEST",
                    "signature": "%s"
                  }]
                }
                """.formatted(java.util.Base64.getEncoder().encodeToString(new byte[64]));
    }

    private HttpServletRequest request(String pathInfo, String body, HttpSession session) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getPathInfo" -> pathInfo;
            case "getContentType" -> "application/json";
            case "getContentLengthLong" -> (long) body.length();
            case "getReader" -> new BufferedReader(new StringReader(body));
            case "getSession" -> session;
            case "setCharacterEncoding" -> null;
            default -> null;
        });
    }

    private FakeSession authenticatedSession() {
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 7L);
        session.attributes.put(SessionAttributes.USERNAME, "farmer.one");
        session.attributes.put(SessionAttributes.ROLE, "FARMER");
        session.attributes.put(SessionAttributes.ORGANIZATION_ID, "farm-1");
        return session;
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();

        private HttpSession proxy() {
            return BatchEventServletTest.proxy(HttpSession.class, (method, args) ->
                    "getAttribute".equals(method) ? attributes.get(args[0]) : null);
        }
    }

    private static final class FakeResponse {
        private final StringWriter writer = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return BatchEventServletTest.proxy(HttpServletResponse.class, (method, args) ->
                    switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(writer);
                        default -> null;
                    });
        }

        private String body() {
            return writer.toString();
        }
    }
}
