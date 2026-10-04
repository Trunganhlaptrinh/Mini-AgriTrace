package controller;

import blockchain.BlockValidationException;
import blockchain.GovernanceValidationException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dal.DuplicateTransactionException;
import dal.PersistenceException;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.Set;
import security.ApiJson;
import service.GovernanceService;
import service.NodeRuntime;

@WebServlet(urlPatterns = {
        "/api/v1/admin/organizations/*",
        "/api/v1/admin/peers/*"
})
public final class AdminGovernanceServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> COMMON_FIELDS = Set.of(
            "eventId", "eventTime", "adminSignature");
    private static final Set<String> KEY_FIELDS = Set.of(
            "keyId", "algorithm", "publicKey", "eventId", "eventTime", "adminSignature");
    private static final Set<String> STATUS_FIELDS = Set.of(
            "status", "eventId", "eventTime", "adminSignature");
    private static final Set<String> PEER_FIELDS = Set.of(
            "peerId", "organizationId", "endpoint", "tlsCertificateFingerprint",
            "eventId", "eventTime", "adminSignature");

    private transient GovernanceService governanceService;

    @Override
    public void init() throws ServletException {
        Object runtime = getServletContext().getAttribute(NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE);
        if (!(runtime instanceof NodeRuntime nodeRuntime)) {
            throw new ServletException("AgriTrace governance runtime is not initialized");
        }
        governanceService = nodeRuntime.governanceService();
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isJson(request.getContentType())) {
            unsupportedMediaType(response);
            return;
        }
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            String servletPath = request.getServletPath();
            if (servletPath.endsWith("/peers")) {
                rejectUnknownFields(body, PEER_FIELDS);
                submit(response, governanceService.registerPeer(
                        requiredString(body, "eventId"),
                        eventTime(requiredString(body, "eventTime")),
                        requiredString(body, "peerId"),
                        requiredString(body, "organizationId"),
                        requiredString(body, "endpoint"),
                        requiredString(body, "tlsCertificateFingerprint"),
                        requiredString(body, "adminSignature")));
                return;
            }

            String[] segments = pathSegments(request.getPathInfo());
            if (segments.length != 2) {
                notFound(response);
                return;
            }
            String organizationId = segments[0];
            if ("keys".equals(segments[1])) {
                rejectUnknownFields(body, KEY_FIELDS);
                submit(response, governanceService.registerOrganizationKey(
                        requiredString(body, "eventId"),
                        eventTime(requiredString(body, "eventTime")),
                        organizationId,
                        requiredString(body, "keyId"),
                        requiredString(body, "algorithm"),
                        requiredString(body, "publicKey"),
                        requiredString(body, "adminSignature")));
            } else if ("status".equals(segments[1])) {
                rejectUnknownFields(body, STATUS_FIELDS);
                submit(response, governanceService.setOrganizationStatus(
                        requiredString(body, "eventId"),
                        eventTime(requiredString(body, "eventTime")),
                        organizationId,
                        requiredString(body, "status"),
                        requiredString(body, "adminSignature")));
            } else {
                notFound(response);
            }
        } catch (IllegalArgumentException exception) {
            invalidRequest(response, exception);
        } catch (GovernanceValidationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (BlockValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (DuplicateTransactionException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Governance transaction persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Transaction service is unavailable", "TRANSACTION_SERVICE_UNAVAILABLE"));
        }
    }

    @Override
    protected void doDelete(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isJson(request.getContentType())) {
            unsupportedMediaType(response);
            return;
        }
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            String servletPath = request.getServletPath();
            String[] segments = pathSegments(request.getPathInfo());
            if (servletPath.endsWith("/organizations")) {
                if (segments.length != 3 || !"keys".equals(segments[1])) {
                    notFound(response);
                    return;
                }
                rejectUnknownFields(body, COMMON_FIELDS);
                submit(response, governanceService.revokeOrganizationKey(
                        requiredString(body, "eventId"),
                        eventTime(requiredString(body, "eventTime")),
                        segments[2],
                        requiredString(body, "adminSignature")));
                return;
            }
            if (servletPath.endsWith("/peers")) {
                if (segments.length != 1) {
                    notFound(response);
                    return;
                }
                rejectUnknownFields(body, COMMON_FIELDS);
                submit(response, governanceService.revokePeer(
                        requiredString(body, "eventId"),
                        eventTime(requiredString(body, "eventTime")),
                        segments[0],
                        requiredString(body, "adminSignature")));
                return;
            }
            notFound(response);
        } catch (IllegalArgumentException exception) {
            invalidRequest(response, exception);
        } catch (GovernanceValidationException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (BlockValidationException exception) {
            ApiJson.write(response, 422,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (DuplicateTransactionException exception) {
            ApiJson.write(response, HttpServletResponse.SC_CONFLICT,
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Governance transaction persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Transaction service is unavailable", "TRANSACTION_SERVICE_UNAVAILABLE"));
        }
    }

    private void submit(
            HttpServletResponse response,
            GovernanceService.RegistrationResult registration
    ) throws IOException {
        JsonObject data = new JsonObject();
        data.addProperty("transactionId", registration.transactionId());
        data.addProperty("status", "PENDING");
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", registration.inserted()
                ? "Governance transaction accepted"
                : "Governance transaction is already pending");
        result.add("data", data);
        ApiJson.write(response, HttpServletResponse.SC_ACCEPTED, result);
    }

    private String[] pathSegments(String pathInfo) {
        if (pathInfo == null || "/".equals(pathInfo)) {
            return new String[0];
        }
        String[] segments = pathInfo.split("/");
        if (segments.length < 2 || !segments[0].isEmpty()) {
            return new String[0];
        }
        for (int index = 1; index < segments.length; index++) {
            if (segments[index].isBlank()) {
                return new String[0];
            }
        }
        return java.util.Arrays.copyOfRange(segments, 1, segments.length);
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the size limit");
        }
        request.setCharacterEncoding("UTF-8");
        BufferedReader reader = request.getReader();
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[2048];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            if (body.length() + count > MAX_REQUEST_CHARS) {
                throw new IllegalArgumentException("Request body exceeds the size limit");
            }
            body.append(buffer, 0, count);
        }
        return body.toString();
    }

    private String requiredString(JsonObject body, String property) {
        JsonElement element = body.get(property);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(property + " must be a non-blank string");
        }
        return element.getAsString();
    }

    private Instant eventTime(String value) {
        try {
            return Instant.from(EVENT_TIME_FORMATTER.parse(value));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "eventTime must be a UTC timestamp with exactly three fractional digits", exception);
        }
    }

    private void rejectUnknownFields(JsonObject body, Set<String> allowedFields) {
        if (!allowedFields.containsAll(body.keySet())) {
            throw new IllegalArgumentException("Request contains unsupported fields");
        }
    }

    private void unsupportedMediaType(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
    }

    private void invalidRequest(HttpServletResponse response, IllegalArgumentException exception)
            throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("Governance endpoint was not found", "NOT_FOUND"));
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }
}
