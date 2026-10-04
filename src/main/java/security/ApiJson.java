package security;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

public final class ApiJson {
    private static final Gson GSON = new Gson();

    private ApiJson() {
    }

    public static JsonObject error(String message, String code) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("success", false);
        envelope.addProperty("message", message);
        JsonObject data = new JsonObject();
        data.addProperty("code", code);
        envelope.add("data", data);
        return envelope;
    }

    public static void write(HttpServletResponse response, int status, Object body)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write(GSON.toJson(body));
    }

    public static JsonObject parseObject(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON request body is required");
        }
        try {
            var parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                throw new IllegalArgumentException("JSON request body must be an object");
            }
            return parsed.getAsJsonObject();
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("JSON request body is malformed", exception);
        }
    }
}
