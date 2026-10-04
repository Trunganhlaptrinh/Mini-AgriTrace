package controller;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.Map;

final class ApiJsonObject {
    private static final Gson GSON = new Gson();

    private ApiJsonObject() {
    }

    static JsonObject from(Map<String, ?> value) {
        return GSON.toJsonTree(value).getAsJsonObject();
    }
}
