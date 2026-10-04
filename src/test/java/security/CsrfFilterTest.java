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

class CsrfFilterTest {
    private final CsrfFilter filter = new CsrfFilter();

    @Test
    void permitsSafeMethodsWithoutCsrfToken() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        FakeSession session = session("csrf-token");

        filter.doFilter(request("GET", "/api/v1/users", session.proxy(), null),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void rejectsStateChangingAuthenticatedRequestsWithMissingOrInvalidCsrfToken() throws Exception {
        for (String providedToken : new String[]{null, "wrong-token"}) {
            FakeResponse response = new FakeResponse();
            AtomicBoolean chainCalled = new AtomicBoolean();

            filter.doFilter(request("POST", "/api/v1/users", session("csrf-token").proxy(), providedToken),
                    response.proxy(), chainCalled(chainCalled));

            assertFalse(chainCalled.get());
            assertEquals(403, response.status);
            assertTrue(response.body().contains("CSRF_TOKEN_INVALID"));
        }
    }

    @Test
    void permitsStateChangingAuthenticatedRequestsWithMatchingCsrfToken() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("POST", "/api/v1/users", session("csrf-token").proxy(), "csrf-token"),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void leavesUnauthenticatedRejectionToAuthenticationFilter() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request("POST", "/api/v1/users", null, null),
                response.proxy(), chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    private HttpServletRequest request(
            String method,
            String servletPath,
            HttpSession session,
            String csrfToken
    ) {
        return proxy(HttpServletRequest.class, (name, args) -> switch (name) {
            case "getMethod" -> method;
            case "getServletPath" -> servletPath;
            case "getSession" -> session;
            case "getHeader" -> "X-CSRF-Token".equals(args[0]) ? csrfToken : null;
            default -> null;
        });
    }

    private FakeSession session(String csrfToken) {
        return new FakeSession(Map.of(
                SessionAttributes.USER_ID, 1L,
                SessionAttributes.CSRF_TOKEN, csrfToken));
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

        private FakeSession(Map<String, Object> attributes) {
            this.attributes = attributes;
        }

        private HttpSession proxy() {
            return CsrfFilterTest.proxy(HttpSession.class, (method, args) ->
                    "getAttribute".equals(method) ? attributes.get(args[0]) : null);
        }
    }

    private static final class FakeResponse {
        private final StringWriter body = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return CsrfFilterTest.proxy(HttpServletResponse.class, (method, args) ->
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
