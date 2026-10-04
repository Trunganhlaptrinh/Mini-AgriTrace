package controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicTraceQrServletTest {
    @Test
    void returnsSvgQrCodeForPublicTracePage() throws Exception {
        PublicTraceQrServlet servlet = new PublicTraceQrServlet(
                "https://agritrace.example/AgriTrace/");
        StringWriter output = new StringWriter();
        AtomicInteger status = new AtomicInteger();
        AtomicInteger contentTypeSet = new AtomicInteger();

        servlet.doGet(request("/MANGO-2026-1"), response(output, status, contentTypeSet));

        String svg = output.toString();
        assertEquals(200, status.get());
        assertTrue(contentTypeSet.get() > 0);
        assertTrue(svg.startsWith("<svg "));
        assertTrue(svg.contains("xmlns=\"http://www.w3.org/2000/svg\""));
        assertTrue(svg.contains("<rect "));
    }

    @Test
    void requiresTrustedHttpsBaseUrlAndRejectsHostHeaderDerivedValues() {
        assertEquals(
                "https://agritrace.example/AgriTrace",
                PublicTraceQrServlet.validatePublicBaseUrl("https://AgriTrace.example/AgriTrace/"));
        assertEquals(
                "http://localhost:8080/AgriTrace",
                PublicTraceQrServlet.validatePublicBaseUrl("http://localhost:8080/AgriTrace"));
        assertThrows(IllegalArgumentException.class,
                () -> PublicTraceQrServlet.validatePublicBaseUrl(null));
        assertThrows(IllegalArgumentException.class,
                () -> PublicTraceQrServlet.validatePublicBaseUrl("http://public.example/AgriTrace"));
        assertThrows(IllegalArgumentException.class,
                () -> PublicTraceQrServlet.validatePublicBaseUrl(
                        "https://user@public.example/AgriTrace"));
        assertThrows(IllegalArgumentException.class,
                () -> PublicTraceQrServlet.validatePublicBaseUrl(
                        "https://public.example/AgriTrace?redirect=attacker.example"));
    }

    private HttpServletRequest request(String pathInfo) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getPathInfo" -> pathInfo;
            default -> null;
        });
    }

    private HttpServletResponse response(
            StringWriter output,
            AtomicInteger status,
            AtomicInteger contentTypeSet
    ) {
        return proxy(HttpServletResponse.class, (method, args) -> switch (method) {
            case "setStatus" -> {
                status.set((Integer) args[0]);
                yield null;
            }
            case "setContentType" -> {
                contentTypeSet.incrementAndGet();
                yield null;
            }
            case "getWriter" -> new PrintWriter(output);
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
}
