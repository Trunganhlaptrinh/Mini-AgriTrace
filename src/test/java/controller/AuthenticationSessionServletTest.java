package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import dal.UserDAO;
import security.AuthenticationThrottle;
import security.PasswordHasher;
import service.AuthenticationService;
import security.SessionAttributes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationSessionServletTest {
    @Test
    void throttlesRepeatedCurrentPasswordFailures() throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        String passwordHash = hasher.hash("correct-current-password".toCharArray());
        UserDAO users = FakeUserDataSource.userDao(Map.of("farmer.one",
                new UserDAO.UserCredential(42, "farmer.one", passwordHash,
                        "FARMER", "farm-1", true, true)));
        AuthenticationSessionServlet servlet = new AuthenticationSessionServlet(
                new AuthenticationService(users, hasher),
                new AuthenticationThrottle(Clock.fixed(
                        Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)));
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 42L);
        session.attributes.put(SessionAttributes.USERNAME, "farmer.one");

        for (int attempt = 0; attempt < 8; attempt++) {
            FakeResponse response = new FakeResponse();
            servlet.doPost(changePasswordRequest(session, "wrong-current-password"), response.proxy());
            assertEquals(401, response.status);
            assertTrue(response.body().contains("INVALID_CREDENTIALS"));
        }

        FakeResponse throttled = new FakeResponse();
        servlet.doPost(changePasswordRequest(session, "correct-current-password"), throttled.proxy());
        assertEquals(429, throttled.status);
        assertEquals("60", throttled.headers.get("Retry-After"));
        assertTrue(throttled.body().contains("AUTHENTICATION_THROTTLED"));
        assertFalse(throttled.body().contains("correct-current-password"));
    }

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

    private HttpServletRequest changePasswordRequest(FakeSession session, String currentPassword) {
        String body = "{\"currentPassword\":\"" + currentPassword
                + "\",\"newPassword\":\"new-password-long-enough\"}";
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getPathInfo" -> "/password";
            case "getSession" -> session.proxy();
            case "getContentType" -> "application/json";
            case "getContentLengthLong" -> (long) body.length();
            case "getReader" -> new java.io.BufferedReader(new java.io.StringReader(body));
            case "getRemoteAddr" -> "192.0.2.40";
            case "setCharacterEncoding" -> null;
            case "toString" -> "FakePasswordRequest";
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
        private final Map<String, String> headers = new HashMap<>();

        private HttpServletResponse proxy() {
            return AuthenticationSessionServletTest.proxy(HttpServletResponse.class,
                    (method, args) -> switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "setHeader" -> {
                            headers.put((String) args[0], (String) args[1]);
                            yield null;
                        }
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
