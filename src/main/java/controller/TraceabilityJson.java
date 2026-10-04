package controller;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import service.TraceabilityService;

final class TraceabilityJson {
    private static final DateTimeFormatter UTC_MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private TraceabilityJson() {
    }

    static JsonObject batch(TraceabilityService.BatchView view) {
        JsonObject batch = new JsonObject();
        batch.addProperty("batchCode", view.batchCode());
        batch.addProperty("currentStatus", view.currentStatus());
        batch.addProperty("farmerOrganizationId", view.farmerOrganizationId());
        batch.addProperty("currentHolderOrganizationId", view.currentHolderOrganizationId());
        addPublicFields(batch, view.publicFields());
        JsonObject data = new JsonObject();
        data.add("batch", batch);
        data.add("events", events(view.events()));
        data.addProperty("chainHeight", view.chainHeight());
        return success("Batch history", data);
    }

    static JsonObject publicTrace(TraceabilityService.PublicTrace trace) {
        JsonObject batch = new JsonObject();
        batch.addProperty("batchCode", trace.batchCode());
        batch.addProperty("status", trace.currentStatus());
        addPublicFields(batch, trace.publicFields());
        if (trace.currentHolderName() == null) {
            batch.add("currentHolder", com.google.gson.JsonNull.INSTANCE);
        } else {
            batch.addProperty("currentHolder", trace.currentHolderName());
        }

        JsonObject verification = new JsonObject();
        verification.addProperty("valid", trace.verification().valid());
        verification.addProperty("chainHeight", trace.verification().chainHeight());
        verification.addProperty("checkedAt", format(trace.verification().checkedAt()));
        JsonObject data = new JsonObject();
        data.add("batch", batch);
        data.add("events", events(trace.events()));
        data.add("verification", verification);
        return success("Traceability record", data);
    }

    private static JsonArray events(java.util.List<TraceabilityService.EventView> events) {
        JsonArray values = new JsonArray();
        for (TraceabilityService.EventView event : events) {
            JsonObject item = new JsonObject();
            item.addProperty("transactionId", event.transactionId());
            item.addProperty("eventType", event.eventType());
            item.addProperty("eventTime", format(event.eventTime()));
            JsonArray signers = new JsonArray();
            for (TraceabilityService.SignerView signer : event.signers()) {
                JsonObject signerJson = new JsonObject();
                signerJson.addProperty("organizationId", signer.organizationId());
                signerJson.addProperty("name", signer.displayName());
                if (signer.role() == null) {
                    signerJson.add("role", com.google.gson.JsonNull.INSTANCE);
                } else {
                    signerJson.addProperty("role", signer.role());
                }
                signers.add(signerJson);
            }
            item.add("signers", signers);
            item.add("publicData", ApiJsonObject.from(event.publicData()));
            item.addProperty("blockHeight", event.blockHeight());
            item.addProperty("blockHash", event.blockHash());
            item.addProperty("blockTimestamp", format(event.blockTimestamp()));
            values.add(item);
        }
        return values;
    }

    private static JsonObject success(String message, JsonObject data) {
        JsonObject response = new JsonObject();
        response.addProperty("success", true);
        response.addProperty("message", message);
        response.add("data", data);
        return response;
    }

    private static String format(Instant instant) {
        return UTC_MILLIS.format(instant);
    }

    private static void addPublicFields(JsonObject target, java.util.Map<String, Object> fields) {
        ApiJsonObject.from(fields).entrySet().forEach(entry -> target.add(entry.getKey(), entry.getValue()));
    }
}
