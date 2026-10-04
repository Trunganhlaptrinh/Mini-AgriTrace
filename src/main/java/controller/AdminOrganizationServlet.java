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

@WebServlet("/api/v1/admin/organizations")
public final class AdminOrganizationServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> REQUEST_FIELDS =
            Set.of("organization", "key", "eventId", "eventTime", "adminSignature");
    private static final Set<String> ORGANIZATION_FIELDS =
            Set.of("organizationId", "type", "name", "province");
    private static final Set<String> KEY_FIELDS =
            Set.of("keyId", "algorithm", "publicKey");

    private transient GovernanceService governanceService;

    public AdminOrganizationServlet() {
    }

    AdminOrganizationServlet(GovernanceService governanceService) {
        this.governanceService = governanceService;
    }

    @Override
    public void init() throws ServletException {
        if (governanceService != null) {
            return;
        }
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
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            rejectUnknownFields(body, REQUEST_FIELDS, "request");
            JsonObject organization = requiredObject(body, "organization");
            JsonObject key = requiredObject(body, "key");
            rejectUnknownFields(organization, ORGANIZATION_FIELDS, "organization");
            rejectUnknownFields(key, KEY_FIELDS, "key");

            String province = optionalString(organization, "province");
            GovernanceService.RegistrationResult registration =
                    governanceService.registerOrganization(
                            requiredString(body, "eventId"),
                            eventTime(requiredString(body, "eventTime")),
                            requiredString(organization, "organizationId"),
                            requiredString(organization, "type"),
                            requiredString(organization, "name"),
                            province,
                            requiredString(key, "keyId"),
                            requiredString(key, "algorithm"),
                            requiredString(key, "publicKey"),
                            requiredString(body, "adminSignature"));

            JsonObject data = new JsonObject();
            data.addProperty("transactionId", registration.transactionId());
            data.addProperty("status", "PENDING");
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", registration.inserted()
                    ? "Organization registration accepted"
                    : "Organization registration is already pending");
            result.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_ACCEPTED, result);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
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
            getServletContext().log("Organization registration persistence failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    ApiJson.error("Transaction service is unavailable", "TRANSACTION_SERVICE_UNAVAILABLE"));
        }
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

    private JsonObject requiredObject(JsonObject body, String property) {
        JsonElement element = body.get(property);
        if (element == null || !element.isJsonObject()) {
            throw new IllegalArgumentException(property + " must be a JSON object");
        }
        return element.getAsJsonObject();
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

    private String optionalString(JsonObject body, String property) {
        JsonElement element = body.get(property);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(property + " must be omitted or a non-blank string");
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

    private void rejectUnknownFields(JsonObject object, Set<String> allowed, String fieldName) {
        if (!allowed.containsAll(object.keySet())) {
            throw new IllegalArgumentException(fieldName + " contains unsupported fields");
        }
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }
}
