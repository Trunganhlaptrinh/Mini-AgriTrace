package network;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.EventType;
import model.ShipmentProposal;
import model.SignatureEnvelope;

public final class ShipmentProposalWireCodec {
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Set<String> FIELDS = Set.of(
            "proposalId", "eventId", "batchCode", "eventTime", "senderOrganizationId",
            "carrierOrganizationId", "recipientOrganizationId", "fromProvince",
            "toProvince", "expiresAt", "signature");
    private static final Set<String> SIGNATURE_FIELDS = Set.of("keyId", "purpose", "value");

    private ShipmentProposalWireCodec() {
    }

    public static String encode(ShipmentProposal proposal) {
        JsonObject body = new JsonObject();
        body.addProperty("proposalId", proposal.proposalId());
        body.addProperty("eventId", proposal.event().eventId());
        body.addProperty("batchCode", proposal.event().batchCode());
        body.addProperty("eventTime", UTC_MILLIS.format(proposal.event().eventTime()));
        body.addProperty("senderOrganizationId", value(proposal, "senderOrganizationId"));
        body.addProperty("carrierOrganizationId", value(proposal, "carrierOrganizationId"));
        body.addProperty("recipientOrganizationId", value(proposal, "recipientOrganizationId"));
        body.addProperty("fromProvince", value(proposal, "fromProvince"));
        body.addProperty("toProvince", value(proposal, "toProvince"));
        body.addProperty("expiresAt", value(proposal, "expiresAt"));
        SignatureEnvelope signature = proposal.event().signatures().get(0);
        JsonObject signatureJson = new JsonObject();
        signatureJson.addProperty("keyId", signature.keyId());
        signatureJson.addProperty("purpose", signature.purpose());
        signatureJson.addProperty("value", signature.signature());
        body.add("signature", signatureJson);
        return body.toString();
    }

    public static ShipmentProposal decode(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("Shipment proposal JSON is malformed", exception);
        }
        if (!parsed.isJsonObject()) {
            throw new IllegalArgumentException("Shipment proposal must be a JSON object");
        }
        JsonObject body = parsed.getAsJsonObject();
        if (!FIELDS.containsAll(body.keySet())) {
            throw new IllegalArgumentException("Shipment proposal contains unsupported fields");
        }
        JsonElement signatureElement = body.get("signature");
        if (signatureElement == null || !signatureElement.isJsonObject()) {
            throw new IllegalArgumentException("signature must be a JSON object");
        }
        JsonObject signatureJson = signatureElement.getAsJsonObject();
        if (!SIGNATURE_FIELDS.containsAll(signatureJson.keySet())) {
            throw new IllegalArgumentException("proposal signature contains unsupported fields");
        }
        SignatureEnvelope signature = new SignatureEnvelope(
                requiredString(body, "senderOrganizationId"),
                requiredString(signatureJson, "keyId"),
                requiredString(signatureJson, "purpose"),
                requiredString(signatureJson, "value"));
        String expiresAt = requiredString(body, "expiresAt");
        Instant expiry;
        try {
            if (!expiresAt.endsWith("Z")) {
                throw new DateTimeParseException("Timestamp must use UTC", expiresAt, expiresAt.length());
            }
            expiry = Instant.parse(expiresAt);
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("expiresAt must be a UTC ISO-8601 timestamp", exception);
        }
        Map<String, Object> data = Map.of(
                "senderOrganizationId", signature.organizationId(),
                "carrierOrganizationId", requiredString(body, "carrierOrganizationId"),
                "recipientOrganizationId", requiredString(body, "recipientOrganizationId"),
                "fromProvince", requiredString(body, "fromProvince"),
                "toProvince", requiredString(body, "toProvince"),
                "expiresAt", expiresAt);
        return new ShipmentProposal(
                requiredString(body, "proposalId"),
                new BatchEvent(
                        "pending",
                        requiredString(body, "eventId"),
                        requiredString(body, "batchCode"),
                        EventType.SHIPPED,
                        eventTime(requiredString(body, "eventTime")),
                        data,
                        List.of(signature)),
                expiry,
                ShipmentProposal.Status.AWAITING_CARRIER,
                null);
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

    private static String value(ShipmentProposal proposal, String key) {
        Object value = proposal.event().data().get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException("Shipment proposal data is missing " + key);
        }
        return text;
    }
}
