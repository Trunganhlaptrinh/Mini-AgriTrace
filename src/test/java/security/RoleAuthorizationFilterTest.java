package security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleAuthorizationFilterTest {
    private final RoleAuthorizationFilter filter = new RoleAuthorizationFilter();

    @Test
    void permitsAdministratorSessions() throws Exception {
        FakeSession session = session("ADMIN");
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request(session.proxy()), response.proxy(),
                chainCalled(chainCalled));

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
    }

    @Test
    void deniesAuthenticatedNonAdministratorSessions() throws Exception {
        FakeSession session = session("FARMER");
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request(session.proxy()), response.proxy(),
                chainCalled(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(403, response.status);
        assertTrue(response.body().contains("FORBIDDEN"));
    }

    @Test
    void deniesRequestsWithoutASession() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        filter.doFilter(request(null), response.proxy(), chainCalled(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(401, response.status);
    }

    private HttpServletRequest request(HttpSession session) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getSession" -> session;
            case "toString" -> "FakeRequest";
            default -> null;
        });
    }

    private FilterChain chainCalled(AtomicBoolean called) {
        return (request, response) -> called.set(true);
    }

    private FakeSession session(String role) {
        FakeSession session = new FakeSession();
        session.attributes.put(SessionAttributes.USER_ID, 1L);
        session.attributes.put(SessionAttributes.ROLE, role);
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
            return RoleAuthorizationFilterTest.proxy(HttpSession.class, (method, args) ->
                    "getAttribute".equals(method) ? attributes.get(args[0]) : null);
        }
    }

    private static final class FakeResponse {
        private final StringWriter body = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return RoleAuthorizationFilterTest.proxy(HttpServletResponse.class, (method, args) ->
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
