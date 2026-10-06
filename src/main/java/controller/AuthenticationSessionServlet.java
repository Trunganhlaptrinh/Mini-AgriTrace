package controller;

import com.google.gson.JsonNull;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dal.PersistenceException;
import dal.UserDAO;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.BufferedReader;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import service.AuthenticationException;
import service.AuthenticationService;
import security.ApiJson;
import security.AuthenticationThrottle;
import security.SessionAttributes;

@WebServlet("/api/v1/auth/*")
public final class AuthenticationSessionServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 8 * 1024;
    private static final int CSRF_TOKEN_BYTES = 32;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private transient AuthenticationService authenticationService;
    private final AuthenticationThrottle authenticationThrottle;

    public AuthenticationSessionServlet() {
        this(null, AuthenticationThrottle.shared());
    }

    AuthenticationSessionServlet(
            AuthenticationService authenticationService,
            AuthenticationThrottle authenticationThrottle
    ) {
        this.authenticationService = authenticationService;
        this.authenticationThrottle = java.util.Objects.requireNonNull(
                authenticationThrottle, "authenticationThrottle");
    }

    @Override
    public void init() {
        if (authenticationService == null) {
            authenticationService = new AuthenticationService(new UserDAO(), new security.PasswordHasher());
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!"/me".equals(request.getPathInfo())) {
            ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiJson.error("Authentication endpoint was not found", "NOT_FOUND"));
            return;
        }
        HttpSession session = request.getSession(false);
        if (session == null) {
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("Authentication is required", "UNAUTHENTICATED"));
            return;
        }
        Object userId = session.getAttribute(SessionAttributes.USER_ID);
        Object username = session.getAttribute(SessionAttributes.USERNAME);
        Object role = session.getAttribute(SessionAttributes.ROLE);
        Object csrfToken = session.getAttribute(SessionAttributes.CSRF_TOKEN);
        if (!(userId instanceof Long)
                || !(username instanceof String)
                || !(role instanceof String)
                || !(csrfToken instanceof String)) {
            session.invalidate();
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("Authentication session is invalid", "INVALID_SESSION"));
            return;
        }

        JsonObject data = new JsonObject();
        data.addProperty("userId", (Long) userId);
        data.addProperty("username", (String) username);
        data.addProperty("role", (String) role);
        Object organizationId = session.getAttribute(SessionAttributes.ORGANIZATION_ID);
        if (organizationId instanceof String organization) {
            data.addProperty("organizationId", organization);
        } else {
            data.add("organizationId", JsonNull.INSTANCE);
        }
        data.addProperty("csrfToken", (String) csrfToken);

        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", "Current account");
        result.add("data", data);
        ApiJson.write(response, HttpServletResponse.SC_OK, result);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        switch (request.getPathInfo() == null ? "" : request.getPathInfo()) {
            case "/logout" -> logout(request, response);
            case "/password" -> changePassword(request, response);
            default -> ApiJson.write(response, HttpServletResponse.SC_NOT_FOUND,
                    ApiJson.error("Authentication endpoint was not found", "NOT_FOUND"));
        }
    }

    private void logout(HttpServletRequest request, HttpServletResponse response) throws IOException {
        HttpSession session = request.getSession(false);
        if (session == null) {
            ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                    ApiJson.error("Authentication is required", "UNAUTHENTICATED"));
            return;
        }
        session.invalidate();
        JsonObject result = new JsonObject();
        result.addProperty("success", true);
        result.addProperty("message", "Logout successful");
        result.add("data", JsonNull.INSTANCE);
        ApiJson.write(response, HttpServletResponse.SC_OK, result);
    }

    private void changePassword(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isJson(request.getContentType())) {
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }
        char[] currentPassword = null;
        char[] newPassword = null;
        String username = null;
        try {
            HttpSession session = request.getSession(false);
            Object sessionUserId = session == null ? null : session.getAttribute(SessionAttributes.USER_ID);
            Object sessionUsername = session == null ? null : session.getAttribute(SessionAttributes.USERNAME);
            if (!(sessionUserId instanceof Long userId) || !(sessionUsername instanceof String)) {
                ApiJson.write(response, HttpServletResponse.SC_UNAUTHORIZED,
                        ApiJson.error("Authentication is required", "UNAUTHENTICATED"));
                return;
            }
            username = (String) sessionUsername;
            long retryAfter = authenticationThrottle.retryAfterSeconds(
                    request.getRemoteAddr(), username);
            if (retryAfter > 0) {
                response.setHeader("Retry-After", Long.toString(retryAfter));
                ApiJson.write(response, 429,
                        ApiJson.error("Authentication is temporarily unavailable. Try again later.",
                                "AUTHENTICATION_THROTTLED"));
                return;
            }
            JsonObject body = ApiJson.parseObject(readBody(request));
            currentPassword = requiredString(body, "currentPassword").toCharArray();
            newPassword = requiredString(body, "newPassword").toCharArray();
            body.remove("currentPassword");
            body.remove("newPassword");
            authenticationService.changePassword(userId, username, currentPassword, newPassword);
            authenticationThrottle.recordSuccess(request.getRemoteAddr(), username);

            byte[] token = new byte[CSRF_TOKEN_BYTES];
            SECURE_RANDOM.nextBytes(token);
            String csrfToken = TOKEN_ENCODER.encodeToString(token);
            Arrays.fill(token, (byte) 0);
            session.setAttribute(SessionAttributes.CSRF_TOKEN, csrfToken);

            JsonObject data = new JsonObject();
            data.addProperty("csrfToken", csrfToken);
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Password changed successfully");
            result.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_OK, result);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            if ("INVALID_CURRENT_PASSWORD".equals(exception.getCode())) {
                authenticationThrottle.recordFailure(
                        request.getRemoteAddr(), username);
            }
            ApiJson.write(response, exception.getHttpStatus(),
                    "INVALID_CURRENT_PASSWORD".equals(exception.getCode())
                            ? ApiJson.error("Current password is incorrect", "INVALID_CREDENTIALS")
                            : ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Password update failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiJson.error("Password service is unavailable", "PASSWORD_SERVICE_UNAVAILABLE"));
        } finally {
            if (currentPassword != null) {
                Arrays.fill(currentPassword, '\0');
            }
            if (newPassword != null) {
                Arrays.fill(newPassword, '\0');
            }
        }
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the size limit");
        }
        request.setCharacterEncoding("UTF-8");
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[1024];
        int count;
        BufferedReader reader = request.getReader();
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

    private boolean isJson(String contentType) {
        return contentType != null
                && contentType.split(";", 2)[0].trim().equalsIgnoreCase("application/json");
    }
}
