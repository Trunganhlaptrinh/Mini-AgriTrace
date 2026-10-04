package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import security.SessionAttributes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationSessionServletTest {
    @Test
    void returnsAccountContextAndCsrfTokenWithoutExposingSessionOrCredentialData() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 42L);
        session.attributes.put(SessionAttributes.USERNAME, "farmer.one");
        session.attributes.put(SessionAttributes.ROLE, "FARMER");
        session.attributes.put(SessionAttributes.ORGANIZATION_ID, "farm-1");
        session.attributes.put(SessionAttributes.CSRF_TOKEN, "csrf-value");

        new AuthenticationSessionServlet().doGet(
                request("/me", session.proxy()), response.proxy());

        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"userId\":42"));
        assertTrue(response.body().contains("\"csrfToken\":\"csrf-value\""));
        assertFalse(response.body().contains("sessionId"));
        assertFalse(response.body().contains("password"));
    }

    @Test
    void logoutInvalidatesSession() throws Exception {
        FakeResponse response = new FakeResponse();
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 42L);

        new AuthenticationSessionServlet().doPost(
                request("/logout", session.proxy()), response.proxy());

        assertTrue(session.invalidated);
        assertEquals(200, response.status);
        assertTrue(response.body().contains("\"success\":true"));
    }

    private HttpServletRequest request(String pathInfo, HttpSession session) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getPathInfo" -> pathInfo;
            case "getSession" -> session;
            case "toString" -> "FakeRequest";
            default -> defaultValue(HttpServletRequest.class, method);
        });
    }

    private static Object defaultValue(Class<?> type, String method) {
        return switch (method) {
            case "getContentLengthLong", "getContentLength" -> 0L;
            case "isCommitted", "isSecure" -> false;
            case "getBufferSize" -> 0;
            default -> null;
        };
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(),
                        args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();
        private boolean invalidated;

        private HttpSession proxy() {
            return AuthenticationSessionServletTest.proxy(HttpSession.class, (method, args) -> switch (method) {
                case "getAttribute" -> attributes.get(args[0]);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                case "invalidate" -> {
                    invalidated = true;
                    attributes.clear();
                    yield null;
                }
                case "toString" -> "FakeSession";
                default -> null;
            });
        }
    }

    private static final class FakeResponse {
        private final StringWriter writer = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return AuthenticationSessionServletTest.proxy(HttpServletResponse.class,
                    (method, args) -> switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(writer);
                        case "toString" -> "FakeResponse";
                        default -> null;
                    });
        }

        private String body() {
            return writer.toString();
        }
    }
}
