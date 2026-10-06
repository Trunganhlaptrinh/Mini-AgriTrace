package security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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

class SecurityHeadersFilterTest {
    @Test
    void setsBrowserSecurityHeadersBeforeContinuingTheResponseChain() throws Exception {
        SecurityHeadersFilter filter = new SecurityHeadersFilter();
        Map<String, String> headers = new HashMap<>();
        AtomicBoolean chainCalled = new AtomicBoolean();
        ServletRequest request = proxy(ServletRequest.class, (method, args) -> null);
        HttpServletResponse response = proxy(HttpServletResponse.class, (method, args) -> {
            if ("setHeader".equals(method)) {
                headers.put((String) args[0], (String) args[1]);
            }
            return null;
        });
        FilterChain chain = (ignoredRequest, ignoredResponse) -> chainCalled.set(true);

        filter.doFilter(request, response, chain);

        assertTrue(chainCalled.get());
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertEquals("DENY", headers.get("X-Frame-Options"));
        assertEquals("strict-origin-when-cross-origin", headers.get("Referrer-Policy"));
        assertEquals("camera=(), microphone=(), geolocation=()", headers.get("Permissions-Policy"));
        assertEquals("default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
                        + "connect-src 'self'; form-action 'self'; base-uri 'self'; object-src 'none'; "
                        + "frame-ancestors 'none'",
                headers.get("Content-Security-Policy"));
        assertFalse(headers.containsKey("Strict-Transport-Security"));
    }

    @Test
    void addsSecurityHeadersToEarlyJsonRejectionsThatDoNotContinueTheFilterChain() throws Exception {
        Map<String, String> headers = new HashMap<>();
        StringWriter body = new StringWriter();
        HttpServletResponse response = proxy(HttpServletResponse.class, (method, args) -> switch (method) {
            case "setHeader" -> {
                headers.put((String) args[0], (String) args[1]);
                yield null;
            }
            case "setStatus", "setCharacterEncoding", "setContentType" -> null;
            case "getWriter" -> new PrintWriter(body);
            default -> null;
        });

        ApiJson.write(response, 401, ApiJson.error("Authentication is required", "UNAUTHENTICATED"));

        assertTrue(headers.containsKey("Content-Security-Policy"));
        assertEquals("nosniff", headers.get("X-Content-Type-Options"));
        assertTrue(body.toString().contains("UNAUTHENTICATED"));
    }

    private static <T> T proxy(Class<T> type, Handler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface Handler {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
