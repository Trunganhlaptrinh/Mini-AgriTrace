package controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import model.ShipmentProposal;
import model.SignatureEnvelope;

final class ShipmentProposalJson {
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private ShipmentProposalJson() {
    }

    static JsonObject proposal(ShipmentProposal proposal, String payloadHash) {
        JsonObject result = new JsonObject();
        result.addProperty("proposalId", proposal.proposalId());
        result.addProperty("eventId", proposal.event().eventId());
        result.addProperty("batchCode", proposal.event().batchCode());
        result.addProperty("eventTime", UTC_MILLIS.format(proposal.event().eventTime()));
        result.addProperty("senderOrganizationId", value(proposal, "senderOrganizationId"));
        result.addProperty("carrierOrganizationId", value(proposal, "carrierOrganizationId"));
        result.addProperty("recipientOrganizationId", value(proposal, "recipientOrganizationId"));
        result.addProperty("fromProvince", value(proposal, "fromProvince"));
        result.addProperty("toProvince", value(proposal, "toProvince"));
        result.addProperty("expiresAt", value(proposal, "expiresAt"));
        result.addProperty("payloadHash", payloadHash);
        result.addProperty("status", proposal.status().name());
        if (proposal.submittedTransactionId() != null) {
            result.addProperty("transactionId", proposal.submittedTransactionId());
        }
        JsonArray signatures = new JsonArray();
        for (SignatureEnvelope signature : proposal.event().signatures()) {
            JsonObject item = new JsonObject();
            item.addProperty("organizationId", signature.organizationId());
            item.addProperty("keyId", signature.keyId());
            item.addProperty("purpose", signature.purpose());
            item.addProperty("value", signature.signature());
            signatures.add(item);
        }
        result.add("signatures", signatures);
        return result;
    }

    static JsonObject success(String message, JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", message);
        response.add("data", data);
        return response;
    }

    static String value(ShipmentProposal proposal, String key) {
        Object value = proposal.event().data().get(key);
        if (!(value instanceof String text)) {
            throw new IllegalStateException("Stored shipment proposal has invalid " + key);
        }
        return text;
    }
}
