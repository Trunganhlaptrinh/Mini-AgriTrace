package controller;

import java.io.BufferedReader;
import java.io.StringReader;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.HashMap;
import dal.UserDAO;
import security.AuthenticationThrottle;
import security.PasswordHasher;
import org.junit.jupiter.api.Test;
import service.AuthenticationService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginServletTest {
    @Test
    void unknownAndWrongPasswordHaveTheSameExternalResponse() throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        String passwordHash = hasher.hash("correct-passphrase".toCharArray());
        UserDAO users = FakeUserDataSource.userDao(Map.of("farmer.one",
                new UserDAO.UserCredential(4, "farmer.one", passwordHash,
                        "FARMER", "farm-1", true, true)));
        AuthenticationService authentication = new AuthenticationService(users, hasher);

        FakeResponse unknown = post(new LoginServlet(authentication, throttle()),
                "{\"username\":\"missing\",\"password\":\"wrong-passphrase\"}");
        FakeResponse wrong = post(new LoginServlet(authentication, throttle()),
                "{\"username\":\"farmer.one\",\"password\":\"wrong-passphrase\"}");

        assertEquals(401, unknown.status);
        assertEquals(unknown.body(), wrong.body());
        assertTrue(unknown.body().contains("Invalid username or password"));
    }

    @Test
    void repeatedFailedLoginAttemptsAreThrottledWithAUniformResponse() throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        String passwordHash = hasher.hash("correct-passphrase".toCharArray());
        UserDAO users = FakeUserDataSource.userDao(Map.of("farmer.one",
                new UserDAO.UserCredential(4, "farmer.one", passwordHash,
                        "FARMER", "farm-1", true, true)));
        LoginServlet servlet = new LoginServlet(
                new AuthenticationService(users, hasher), throttle());
        for (int attempt = 0; attempt < 8; attempt++) {
            assertEquals(401, post(servlet,
                    "{\"username\":\"farmer.one\",\"password\":\"wrong-passphrase\"}").status);
        }

        FakeResponse throttled = post(servlet,
                "{\"username\":\"farmer.one\",\"password\":\"correct-passphrase\"}");
        assertEquals(429, throttled.status);
        assertEquals("60", throttled.headers.get("Retry-After"));
        assertTrue(throttled.body().contains("AUTHENTICATION_THROTTLED"));
        assertTrue(!throttled.body().contains("correct-passphrase"));
    }

    @Test
    void validLoginSucceedsAfterTheShortThrottleWindowExpires() throws Exception {
        PasswordHasher hasher = new PasswordHasher();
        String passwordHash = hasher.hash("correct-passphrase".toCharArray());
        UserDAO users = FakeUserDataSource.userDao(Map.of("farmer.one",
                new UserDAO.UserCredential(4, "farmer.one", passwordHash,
                        "FARMER", "farm-1", true, true)));
        MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));
        AuthenticationThrottle throttle = new AuthenticationThrottle(clock);
        for (int attempt = 0; attempt < 8; attempt++) {
            throttle.recordFailure("192.0.2." + attempt, "farmer.one");
        }
        clock.advance(java.time.Duration.ofSeconds(61));

        FakeResponse response = new FakeResponse();
        LoginServlet servlet = new LoginServlet(new AuthenticationService(users, hasher), throttle);
        servlet.doPost(request("{\"username\":\"farmer.one\",\"password\":\"correct-passphrase\"}",
                new FakeSession()), response.proxy());

        assertEquals(200, response.status);
        assertTrue(response.body().contains("Login successful"));
        assertTrue(response.body().contains("\"role\":\"FARMER\""));
    }

    private AuthenticationThrottle throttle() {
        return new AuthenticationThrottle(Clock.fixed(
                Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
    }

    private FakeResponse post(LoginServlet servlet, String body) throws Exception {
        FakeResponse response = new FakeResponse();
        servlet.doPost(request(body), response.proxy());
        return response;
    }

    private jakarta.servlet.http.HttpServletRequest request(String body) {
        return request(body, null);
    }

    private jakarta.servlet.http.HttpServletRequest request(String body, FakeSession session) {
        FakeSession[] current = {null};
        return proxy(jakarta.servlet.http.HttpServletRequest.class, (method, args) -> switch (method) {
            case "getContentType" -> "application/json";
            case "getContentLengthLong" -> (long) body.length();
            case "getReader" -> new BufferedReader(new StringReader(body));
            case "getRemoteAddr" -> "192.0.2.20";
            case "setCharacterEncoding" -> null;
            case "getSession" -> {
                if (Boolean.TRUE.equals(args[0]) && current[0] == null) {
                    current[0] = session;
                }
                yield current[0] == null ? null : current[0].proxy();
            }
            default -> null;
        });
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static final class FakeResponse {
        private int status;
        private final StringBuilder body = new StringBuilder();
        private final java.util.Map<String, String> headers = new java.util.HashMap<>();

        private jakarta.servlet.http.HttpServletResponse proxy() {
            return LoginServletTest.proxy(jakarta.servlet.http.HttpServletResponse.class,
                    (method, args) -> switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setHeader" -> {
                            headers.put((String) args[0], (String) args[1]);
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "getWriter" -> new java.io.PrintWriter(new java.io.Writer() {
                            @Override
                            public void write(char[] chars, int offset, int length) {
                                body.append(chars, offset, length);
                            }

                            @Override
                            public void flush() {
                            }

                            @Override
                            public void close() {
                            }
                        });
                        default -> null;
                    });
        }

        private String body() {
            return body.toString();
        }
    }

    private static final class FakeSession {
        private final Map<String, Object> attributes = new HashMap<>();

        private jakarta.servlet.http.HttpSession proxy() {
            return LoginServletTest.proxy(jakarta.servlet.http.HttpSession.class, (method, args) -> switch (method) {
                case "getAttribute" -> attributes.get(args[0]);
                case "setAttribute" -> {
                    attributes.put((String) args[0], args[1]);
                    yield null;
                }
                case "invalidate" -> {
                    attributes.clear();
                    yield null;
                }
                default -> null;
            });
        }
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(java.time.Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
