package controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dal.PersistenceException;
import dal.UserDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import security.ApiJson;
import security.PasswordHasher;
import security.SessionAttributes;
import service.AdminUserService;
import service.AuthenticatedAccount;
import service.AuthenticationException;

@WebServlet("/api/v1/admin/users/*")
public final class AdminUserServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private static final Set<String> ROLES = Set.of(
            "ADMIN", "FARMER", "CARRIER", "WAREHOUSE", "RETAILER");

    private transient AdminUserService adminUserService;

    @Override
    public void init() {
        adminUserService = new AdminUserService(new UserDAO(), new PasswordHasher());
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        if ("PATCH".equalsIgnoreCase(request.getMethod())) {
            handlePatch(request, response);
            return;
        }
        super.service(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (request.getPathInfo() != null && !"/".equals(request.getPathInfo())) {
            notFound(response);
            return;
        }
        if (!isJson(request.getContentType())) {
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }

        char[] temporaryPassword = null;
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            String username = requiredString(body, "username");
            temporaryPassword = requiredString(body, "temporaryPassword").toCharArray();
            String role = requiredString(body, "role");
            if (!ROLES.contains(role)) {
                throw new IllegalArgumentException("role is not supported");
            }
            String organizationId = optionalString(body, "organizationId");
            long userId = adminUserService.createUser(
                    currentAccount(request), username, temporaryPassword, role, organizationId);

            JsonObject data = new JsonObject();
            data.addProperty("userId", userId);
            data.addProperty("username", username);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "User created");
            result.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_CREATED, result);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("User account creation failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiJson.error("User service is unavailable", "USER_SERVICE_UNAVAILABLE"));
        } finally {
            if (temporaryPassword != null) {
                Arrays.fill(temporaryPassword, '\0');
            }
        }
    }

    private void handlePatch(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        Long userId = userIdFromPath(request.getPathInfo());
        if (userId == null) {
            notFound(response);
            return;
        }
        if (!isJson(request.getContentType())) {
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            JsonElement active = body.get("isActive");
            if (active == null || !active.isJsonPrimitive()
                    || !active.getAsJsonPrimitive().isBoolean()) {
                throw new IllegalArgumentException("isActive must be a boolean");
            }
            adminUserService.setUserActive(currentAccount(request), userId, active.getAsBoolean());
            JsonObject data = new JsonObject();
            data.addProperty("userId", userId);
            data.addProperty("isActive", active.getAsBoolean());
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "User status updated");
            result.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_OK, result);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("User account status update failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiJson.error("User service is unavailable", "USER_SERVICE_UNAVAILABLE"));
        }
    }

    private AuthenticatedAccount currentAccount(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null
                || !(session.getAttribute(SessionAttributes.USER_ID) instanceof Long userId)
                || !(session.getAttribute(SessionAttributes.USERNAME) instanceof String username)
                || !(session.getAttribute(SessionAttributes.ROLE) instanceof String role)) {
            throw new AuthenticationException(
                    "UNAUTHENTICATED", "Authentication is required", 401);
        }
        Object organizationId = session.getAttribute(SessionAttributes.ORGANIZATION_ID);
        return new AuthenticatedAccount(
                userId, username, role, organizationId instanceof String value ? value : null);
    }

    private Long userIdFromPath(String pathInfo) {
        if (pathInfo == null) {
            return null;
        }
        String[] segments = pathInfo.split("/");
        if (segments.length != 3 || !segments[0].isEmpty() || segments[1].isEmpty()
                || !"status".equals(segments[2])) {
            return null;
        }
        try {
            long userId = Long.parseLong(segments[1]);
            return userId > 0 ? userId : null;
        } catch (NumberFormatException exception) {
            return null;
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
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(property + " must be a string");
        }
        return element.getAsString();
    }

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }

    private void notFound(HttpServletResponse response) throws IOException {
        ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                ApiJson.error("User endpoint was not found", "NOT_FOUND"));
    }
}
