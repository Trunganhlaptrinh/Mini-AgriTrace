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

class AdminOrganizationServletTest {
    @Test
    void rejectsNonJsonRegistrationRequests() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminOrganizationServlet().doPost(
                request("text/plain", "{}", 2),
                response.proxy());

        assertEquals(415, response.status);
        assertTrue(response.body().contains("UNSUPPORTED_MEDIA_TYPE"));
    }

    @Test
    void rejectsUnsupportedRequestFieldsInsteadOfSilentlyIgnoringThem() throws Exception {
        FakeResponse response = new FakeResponse();

        new AdminOrganizationServlet().doPost(
                request("application/json", "{\"unexpected\":true}", 19),
                response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("unsupported fields"));
    }

    @Test
    void requiresGovernanceEventTimeToHaveExactlyMillisecondPrecision() throws Exception {
        String body = """
                {
                  "organization": {
                    "organizationId": "org-farm-001",
                    "type": "FARMER",
                    "name": "Mekong Mango Farm"
                  },
                  "key": {
                    "keyId": "org-farm-001-key-1",
                    "algorithm": "ECDSA_P256_SHA256",
                    "publicKey": "encoded-key"
                  },
                  "eventId": "event-1",
                  "eventTime": "2026-10-04T10:00:00Z",
                  "adminSignature": "encoded-signature"
                }
                """;
        FakeResponse response = new FakeResponse();

        new AdminOrganizationServlet().doPost(
                request("application/json", body, body.length()),
                response.proxy());

        assertEquals(400, response.status);
        assertTrue(response.body().contains("exactly three fractional digits"));
    }

    private HttpServletRequest request(String contentType, String body, int contentLength) {
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getContentType" -> contentType;
            case "getContentLengthLong" -> (long) contentLength;
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
            return AdminOrganizationServletTest.proxy(HttpServletResponse.class, (method, args) ->
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
