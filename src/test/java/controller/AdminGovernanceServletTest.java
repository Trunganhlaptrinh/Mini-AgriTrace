package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminGovernanceServletTest {
    @Test
    void rejectsUnsupportedMediaTypeForGovernanceOperations() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminGovernanceServlet().doPost(
                request("/api/v1/admin/peers", null, "text/plain", "{}"),
                response.proxy());

        assertEquals(415, response.status);
        assertTrue(response.body().contains("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsUnknownOrganizationGovernancePath() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminGovernanceServlet().doPost(
                request("/api/v1/admin/organizations", "/org-1/unknown",
                        "application/json", "{}"),
                response.proxy());

        assertEquals(404, response.status);
        assertTrue(response.body().contains("NOT_FOUND"));
    }

    @Test
    void requiresSignedFieldsWhenRegisteringAnOrganizationKey() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminGovernanceServlet().doPost(
                request("/api/v1/admin/organizations", "/org-1/keys",
                        "application/json", "{}"),
                response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("eventId must be a non-blank string"));
    }

    @Test
    void rejectsMalformedPeerRevocationPath() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminGovernanceServlet().doDelete(
                request("/api/v1/admin/peers", "/peer-1/extra",
                        "application/json", "{}"),
                response.proxy());

        assertEquals(404, response.status);
        assertTrue(response.body().contains("NOT_FOUND"));
    }

    private HttpServletRequest request(
            String servletPath,
            String pathInfo,
            String contentType,
            String body
    ) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getServletPath" -> servletPath;
            case "getPathInfo" -> pathInfo;
            case "getContentType" -> contentType;
            case "getContentLengthLong" -> (long) body.length();
            case "getReader" -> new BufferedReader(new StringReader(body));
            case "setCharacterEncoding" -> null;
            default -> null;
        });
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
            return AdminGovernanceServletTest.proxy(HttpServletResponse.class, (method, args) ->
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
