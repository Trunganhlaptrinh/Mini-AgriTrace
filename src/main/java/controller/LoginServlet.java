package controller;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dal.PersistenceException;
import dal.UserDAO;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.io.BufferedReader;
import java.util.Arrays;
import java.util.Base64;
import java.security.SecureRandom;
import security.ApiJson;
import security.PasswordHasher;
import security.SessionAttributes;
import service.AuthenticatedAccount;
import service.AuthenticationException;
import service.AuthenticationService;

@WebServlet("/api/v1/auth/login")
public final class LoginServlet extends HttpServlet {
    private static final int MAX_REQUEST_CHARS = 16 * 1024;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder TOKEN_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private transient AuthenticationService authenticationService;

    @Override
    public void init() throws ServletException {
        authenticationService = new AuthenticationService(new UserDAO(), new PasswordHasher());
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        if (!isJson(request.getContentType())) {
            ApiJson.write(response, HttpServletResponse.SC_UNSUPPORTED_MEDIA_TYPE,
                    ApiJson.error("Content-Type must be application/json", "UNSUPPORTED_MEDIA_TYPE"));
            return;
        }

        char[] password = null;
        try {
            JsonObject body = ApiJson.parseObject(readBody(request));
            String username = requiredString(body, "username");
            password = requiredString(body, "password").toCharArray();
            body.remove("password");
            AuthenticatedAccount account = authenticationService.authenticate(username, password);
            createAuthenticatedSession(request, account);

            JsonObject data = new JsonObject();
            data.addProperty("userId", account.userId());
            data.addProperty("username", account.username());
            data.addProperty("role", account.role());
            if (account.organizationId() == null) {
                data.add("organizationId", com.google.gson.JsonNull.INSTANCE);
            } else {
                data.addProperty("organizationId", account.organizationId());
            }
            data.addProperty("csrfToken",
                    request.getSession(false).getAttribute(SessionAttributes.CSRF_TOKEN).toString());
            JsonObject result = new JsonObject();
            result.addProperty("success", true);
            result.addProperty("message", "Login successful");
            result.add("data", data);
            ApiJson.write(response, HttpServletResponse.SC_OK, result);
        } catch (IllegalArgumentException exception) {
            ApiJson.write(response, HttpServletResponse.SC_BAD_REQUEST,
                    ApiJson.error(exception.getMessage(), "INVALID_REQUEST"));
        } catch (AuthenticationException exception) {
            ApiJson.write(response, exception.getHttpStatus(),
                    ApiJson.error(exception.getMessage(), exception.getCode()));
        } catch (PersistenceException exception) {
            getServletContext().log("Login account lookup failed", exception);
            ApiJson.write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ApiJson.error("Authentication service is unavailable", "AUTHENTICATION_UNAVAILABLE"));
        } finally {
            if (password != null) {
                Arrays.fill(password, '\0');
            }
        }
    }

    private void createAuthenticatedSession(
            HttpServletRequest request,
            AuthenticatedAccount account
    ) {
        HttpSession previous = request.getSession(false);
        if (previous != null) {
            previous.invalidate();
        }
        HttpSession session = request.getSession(true);
        byte[] token = new byte[32];
        SECURE_RANDOM.nextBytes(token);
        session.setAttribute(SessionAttributes.USER_ID, account.userId());
        session.setAttribute(SessionAttributes.USERNAME, account.username());
        session.setAttribute(SessionAttributes.ROLE, account.role());
        if (account.organizationId() != null) {
            session.setAttribute(SessionAttributes.ORGANIZATION_ID, account.organizationId());
        }
        session.setAttribute(SessionAttributes.CSRF_TOKEN, TOKEN_ENCODER.encodeToString(token));
        Arrays.fill(token, (byte) 0);
    }

    private String readBody(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_REQUEST_CHARS) {
            throw new IllegalArgumentException("Request body exceeds the size limit");
        }
        request.setCharacterEncoding("UTF-8");
        StringBuilder body = new StringBuilder();
        char[] buffer = new char[2048];
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
