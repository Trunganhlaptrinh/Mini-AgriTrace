package network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.EventType;
import model.SignatureEnvelope;

public final class ShipmentEndorsementWireCodec {
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> FIELDS = Set.of(
            "proposalId", "transactionId", "eventId", "batchCode", "eventType", "eventTime",
            "data", "signatures");
    private static final Set<String> SIGNATURE_FIELDS =
            Set.of("organizationId", "keyId", "purpose", "signature");

    private ShipmentEndorsementWireCodec() {
    }

    public static String encode(String proposalId, BatchEvent event) {
        JsonObject message = new JsonObject();
        message.addProperty("proposalId", proposalId);
        message.addProperty("transactionId", event.transactionId());
        message.addProperty("eventId", event.eventId());
        message.addProperty("batchCode", event.batchCode());
        message.addProperty("eventType", event.eventType().name());
        message.addProperty("eventTime", UTC_MILLIS.format(event.eventTime()));
        message.add("data", new com.google.gson.Gson().toJsonTree(event.data()));
        com.google.gson.JsonArray signatures = new com.google.gson.JsonArray();
        for (SignatureEnvelope envelope : event.signatures()) {
            JsonObject item = new JsonObject();
            item.addProperty("organizationId", envelope.organizationId());
            item.addProperty("keyId", envelope.keyId());
            item.addProperty("purpose", envelope.purpose());
            item.addProperty("signature", envelope.signature());
            signatures.add(item);
        }
        message.add("signatures", signatures);
        return message.toString();
    }

    public static Endorsement decode(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("Shipment endorsement JSON is malformed", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Shipment endorsement must be a JSON object");
        }
        JsonObject body = parsed.getAsJsonObject();
        if (!FIELDS.containsAll(body.keySet())) {
            throw new IllegalArgumentException("Shipment endorsement contains unsupported fields");
        }
        JsonElement dataElement = body.get("data");
        JsonElement signaturesElement = body.get("signatures");
        if (dataElement == null || !dataElement.isJsonObject()
                || signaturesElement == null || !signaturesElement.isJsonArray()) {
            throw new IllegalArgumentException("Shipment endorsement data and signatures are required");
        }
        List<SignatureEnvelope> signatures = new ArrayList<>();
        for (JsonElement element : signaturesElement.getAsJsonArray()) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Shipment endorsement signatures must be objects");
            }
            JsonObject signature = element.getAsJsonObject();
            if (!SIGNATURE_FIELDS.containsAll(signature.keySet())) {
                throw new IllegalArgumentException("Shipment endorsement signature has unsupported fields");
            }
            signatures.add(new SignatureEnvelope(
                    requiredString(signature, "organizationId"),
                    requiredString(signature, "keyId"),
                    requiredString(signature, "purpose"),
                    requiredString(signature, "signature")));
        }
        EventType eventType;
        try {
            eventType = EventType.valueOf(requiredString(body, "eventType"));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Shipment endorsement eventType is invalid", exception);
        }
        if (eventType != EventType.SHIPPED) {
            throw new IllegalArgumentException("Shipment endorsement must contain a SHIPPED event");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        dataElement.getAsJsonObject().entrySet().forEach(entry ->
                data.put(entry.getKey(), jsonValue(entry.getValue())));
        BatchEvent event = new BatchEvent(
                requiredString(body, "transactionId"),
                requiredString(body, "eventId"),
                requiredString(body, "batchCode"),
                eventType,
                eventTime(requiredString(body, "eventTime")),
                data,
                signatures);
        return new Endorsement(requiredString(body, "proposalId"), event);
    }

    private static Object jsonValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            Map<String, Object> value = new LinkedHashMap<>();
            element.getAsJsonObject().entrySet().forEach(entry ->
                    value.put(entry.getKey(), jsonValue(entry.getValue())));
            return value;
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            element.getAsJsonArray().forEach(item -> values.add(jsonValue(item)));
            return java.util.Collections.unmodifiableList(values);
        }
        if (element.getAsJsonPrimitive().isBoolean()) {
            return element.getAsBoolean();
        }
        if (element.getAsJsonPrimitive().isNumber()) {
            return element.getAsBigDecimal();
        }
        return element.getAsString();
    }

    private static Instant eventTime(String value) {
        try {
            return Instant.from(UTC_MILLIS.parse(value));
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException(
                    "eventTime must be a UTC timestamp with exactly three fractional digits", exception);
        }
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return element.getAsString();
    }

    public record Endorsement(String proposalId, BatchEvent event) {
    }
}
