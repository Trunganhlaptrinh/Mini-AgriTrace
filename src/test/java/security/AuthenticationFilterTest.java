package security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationFilterTest {
    private final AuthenticationFilter filter = new AuthenticationFilter(userId -> true);

    @Test
    void permitsLoginWithoutAnExistingSession() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("POST", "/api/v1/auth/login", null), response.proxy(),
                chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void rejectsUnauthenticatedProtectedRequests() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("GET", "/api/v1/auth/me", null), response.proxy(),
                chainCalled(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(401, response.status);
        assertTrue(response.body().contains("UNAUTHENTICATED"));
    }

    @Test
    void permitsRequestsWithAnAuthenticatedSession() throws Exception {
        FakeSession session = new FakeSession(Map.of(SessionAttributes.USER_ID, 10L));
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("GET", "/api/v1/auth/me", session.proxy()),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void invalidatesSessionWhenAccountOrCanonicalOrganizationIsUnavailable() throws Exception {
        for (String path : new String[]{
                "/api/v1/auth/me", "/api/v1/shipments/inbox", "/api/v1/batches/LOT-1/events"
        }) {
            AuthenticationFilter unavailableFilter = new AuthenticationFilter(userId -> false);
            FakeSession session = new FakeSession(Map.of(SessionAttributes.USER_ID, 10L));
            FakeResponse response = new FakeResponse();
            AtomicBoolean chainCalled = new AtomicBoolean();

            unavailableFilter.doFilter(request("GET", path, session.proxy()),
                    response.proxy(), chainCalled(chainCalled));

            assertFalse(chainCalled.get(), path);
            assertEquals(401, response.status, path);
            assertTrue(session.invalidated, path);
        }
    }

    @Test
    void permitsAnonymousGetForServletMappedPublicTracePath() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("GET", "/api/v1/public/batches", "/MANGO-1/trace", null),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void permitsAnonymousGetForPublicQrAndNetworkMetadata() throws Exception {
        for (String[] route : new String[][]{
                {"GET", "/api/v1/public/trace-qr", "/MANGO-1"},
                {"GET", "/api/v1/network", null}
        }) {
            FakeResponse response = new FakeResponse();
            AtomicBoolean chainCalled = new AtomicBoolean();

            filter.doFilter(request(route[0], route[1], route[2], null),
                    response.proxy(), chainCalled(chainCalled));

            assertTrue(chainCalled.get());
            assertEquals(0, response.status);
        }
    }

    @Test
    void doesNotBypassAuthenticationForNonGetOrMalformedPublicTracePath() throws Exception {
        for (String[] route : new String[][]{
                {"POST", "/api/v1/public/batches", "/MANGO-1/trace"},
                {"GET", "/api/v1/public/batches", "/MANGO-1/trace/extra"},
                {"GET", "/api/v1/public/batches/MANGO-1/trace", null}
        }) {
            FakeResponse response = new FakeResponse();
            AtomicBoolean chainCalled = new AtomicBoolean();

            filter.doFilter(request(route[0], route[1], route[2], null),
                    response.proxy(), chainCalled(chainCalled));

            assertFalse(chainCalled.get());
            assertEquals(401, response.status);
        }
    }

    @Test
    void letsInternalPeerRequestsReachMutualTlsAuthorizationWithoutUserSessions() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("POST", "/api/v1/internal/p2p/shipment-proposals", null),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    private HttpServletRequest request(String method, String servletPath, HttpSession session) {
        return request(method, servletPath, null, session);
    }

    private HttpServletRequest request(
            String method,
            String servletPath,
            String pathInfo,
            HttpSession session
    ) {
        return proxy(HttpServletRequest.class, (name, args) -> switch (name) {
            case "getMethod" -> method;
            case "getServletPath" -> servletPath;
            case "getPathInfo" -> pathInfo;
            case "getSession" -> session;
            default -> null;
        });
    }

    private FilterChain chainCalled(AtomicBoolean called) {
        return (request, response) -> called.set(true);
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
        private final Map<String, Object> attributes;
        private boolean invalidated;

        private FakeSession(Map<String, Object> attributes) {
            this.attributes = attributes;
        }

        private HttpSession proxy() {
            return AuthenticationFilterTest.proxy(HttpSession.class, (method, args) -> switch (method) {
                case "getAttribute" -> attributes.get(args[0]);
                case "invalidate" -> {
                    invalidated = true;
                    yield null;
                }
                default -> null;
            });
        }
    }

    private static final class FakeResponse {
        private final StringWriter body = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return AuthenticationFilterTest.proxy(HttpServletResponse.class, (method, args) ->
                    switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(body);
                        default -> null;
                    });
        }

        private String body() {
            return body.toString();
        }
    }
}
