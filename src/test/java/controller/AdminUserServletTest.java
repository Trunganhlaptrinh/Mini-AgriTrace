package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import security.SessionAttributes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminUserServletTest {
    @Test
    void dispatchesPatchAndRequiresAnAuthenticatedSession() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminUserServlet().service(
                request("PATCH", "/42/status", "application/json", "{\"isActive\":false}", null),
                response.proxy());

        assertEquals(401, response.status);
        assertTrue(response.body().contains("UNAUTHENTICATED"));
    }

    @Test
    void rejectsInvalidPatchPathBeforeReadingRequestBody() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminUserServlet().service(
                request("PATCH", "/not-a-user/status", "application/json", "", null),
                response.proxy());

        assertEquals(404, response.status);
        assertTrue(response.body().contains("NOT_FOUND"));
    }

    @Test
    void validatesPatchStatusAsBoolean() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeSession session = adminSession();

        new AdminUserServlet().service(
                request("PATCH", "/42/status", "application/json",
                        "{\"isActive\":\"false\"}", session.proxy()),
                response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("INVALID_REQUEST"));
    }

    @Test
    void rejectsUnsupportedContentTypeWhenCreatingAUser() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminUserServlet().doPost(
                request("POST", "/", "text/plain", "{}", null),
                response.proxy());

        assertEquals(415, response.status);
        assertTrue(response.body().contains("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsUserCreationWithoutAnAuthenticatedSession() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminUserServlet().doPost(
                request("POST", "/", "application/json",
                        "{\"username\":\"farmer.one\",\"temporaryPassword\":\"temporary-password\","
                                + "\"role\":\"FARMER\",\"organizationId\":\"farm-1\"}",
                        null),
                response.proxy());

        assertEquals(401, response.status);
        assertTrue(response.body().contains("UNAUTHENTICATED"));
    }

    private static HttpServletRequest request(
            String method,
            String pathInfo,
            String contentType,
            String body,
            HttpSession session
    ) {
        return proxy(HttpServletRequest.class, (name, args) -> switch (name) {
            case "getMethod" -> method;
            case "getPathInfo" -> pathInfo;
            case "getContentType" -> contentType;
            case "getContentLengthLong" -> (long) body.length();
            case "getReader" -> new BufferedReader(new StringReader(body));
            case "setCharacterEncoding" -> null;
            case "getSession" -> session;
            default -> null;
        });
    }

    private static FakeSession adminSession() {
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(SessionAttributes.USER_ID, 7L);
        attributes.put(SessionAttributes.USERNAME, "admin.one");
        attributes.put(SessionAttributes.ROLE, "ADMIN");
        return new FakeSession(attributes);
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

    private record FakeSession(Map<String, Object> attributes) {
        private HttpSession proxy() {
            return AdminUserServletTest.proxy(HttpSession.class, (method, args) ->
                    "getAttribute".equals(method) ? attributes.get(args[0]) : null);
        }
    }

    private static final class FakeResponse {
        private final StringWriter writer = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return AdminUserServletTest.proxy(HttpServletResponse.class, (method, args) ->
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
