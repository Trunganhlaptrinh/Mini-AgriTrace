package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Optional;
import model.TransactionStatus;
import model.TransactionStatusSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionStatusServletTest {
    private static final String TRANSACTION_ID = "a".repeat(64);

    @Test
    void returnsPendingStatusWithoutBlockMetadata() throws Exception {
        FakeResponse response = new FakeResponse();
        TransactionStatusSnapshot snapshot = snapshot(
                TransactionStatus.PENDING, null, null, null, null);

        new TransactionStatusServlet(id -> Optional.of(snapshot))
                .doGet(request("/" + TRANSACTION_ID), response.proxy());

        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"status\":\"PENDING\""));
        assertFalse(response.body().contains("\"block\""));
    }

    @Test
    void returnsCanonicalBlockDetailsForConfirmedStatus() throws Exception {
        FakeResponse response = new FakeResponse();
        TransactionStatusSnapshot snapshot = snapshot(
                TransactionStatus.CONFIRMED,
                null,
                42L,
                "b".repeat(64),
                Instant.parse("2026-10-04T10:00:00.000Z"));

        new TransactionStatusServlet(id -> Optional.of(snapshot))
                .doGet(request("/" + TRANSACTION_ID), response.proxy());

        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"height\":42"));
        assertTrue(response.body().contains("\"hash\":\"" + "b".repeat(64) + "\""));
        assertTrue(response.body().contains("\"timestamp\":\"2026-10-04T10:00:00.000Z\""));
    }

    @Test
    void returnsRejectionCodeForRejectedStatus() throws Exception {
        FakeResponse response = new FakeResponse();
        TransactionStatusSnapshot snapshot = snapshot(
                TransactionStatus.REJECTED, "INVALID_BLOCK_STATE", null, null, null);

        new TransactionStatusServlet(id -> Optional.of(snapshot))
                .doGet(request("/" + TRANSACTION_ID), response.proxy());

        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"rejectionCode\":\"INVALID_BLOCK_STATE\""));
        assertFalse(response.body().contains("\"block\""));
    }

    @Test
    void returnsNotFoundForInvalidOrUnknownTransactionIds() throws Exception {
        FakeResponse invalidResponse = new FakeResponse();
        new TransactionStatusServlet(id -> Optional.empty())
                .doGet(request("/not-a-hash"), invalidResponse.proxy());

        assertEquals(404, invalidResponse.status);

        FakeResponse unknownResponse = new FakeResponse();
        new TransactionStatusServlet(id -> Optional.empty())
                .doGet(request("/" + TRANSACTION_ID), unknownResponse.proxy());

        assertEquals(404, unknownResponse.status);
        assertTrue(unknownResponse.body().contains("TRANSACTION_NOT_FOUND"));
    }

    private TransactionStatusSnapshot snapshot(
            TransactionStatus status,
            String rejectionCode,
            Long height,
            String blockHash,
            Instant blockTimestamp
    ) {
        return new TransactionStatusSnapshot(
                TRANSACTION_ID,
                "event-1",
                "HARVESTED",
                "c".repeat(64),
                status,
                rejectionCode,
                Instant.parse("2026-10-04T10:01:00.000Z"),
                height,
                blockHash,
                blockTimestamp);
    }

    private HttpServletRequest request(String pathInfo) {
        return proxy(HttpServletRequest.class, (method, args) ->
                "getPathInfo".equals(method) ? pathInfo : null);
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

    private static final class FakeResponse {
        private final StringWriter writer = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return TransactionStatusServletTest.proxy(HttpServletResponse.class, (method, args) ->
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
