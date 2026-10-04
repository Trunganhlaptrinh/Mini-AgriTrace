package blockchain;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.LedgerTransaction;
import model.SignatureEnvelope;

public final class LedgerTransactionCodec {
    private LedgerTransactionCodec() {
    }

    public static LedgerTransaction decode(
            String networkId,
            String transactionId,
            String eventId,
            String transactionType,
            String payloadJson,
            String payloadHash,
            String signaturesJson
    ) {
        if (networkId == null || networkId.isBlank()
                || transactionId == null || eventId == null || transactionType == null
                || payloadJson == null || payloadHash == null || signaturesJson == null) {
            throw new IllegalArgumentException("Stored transaction fields must not be null");
        }
        JsonObject payload = parseObject(payloadJson, "payload");
        if (!networkId.equals(requiredString(payload, "networkId"))
                || !eventId.equals(requiredString(payload, "eventId"))
                || !transactionType.equals(requiredString(payload, "eventType"))) {
            throw new IllegalArgumentException("Stored transaction columns do not match its payload");
        }
        Instant eventTime = Instant.parse(requiredString(payload, "eventTime"));
        JsonArray signatures = JsonParser.parseString(signaturesJson).getAsJsonArray();

        LedgerTransaction transaction;
        if (isBatchEventType(transactionType)) {
            transaction = decodeBatchEvent(
                    transactionId, eventId, transactionType, eventTime, payload, signatures);
            if (!payloadHash.equals(TransactionCodec.payloadHash(networkId, (BatchEvent) transaction))
                    || !transactionId.equals(TransactionCodec.transactionId(networkId, (BatchEvent) transaction))) {
                throw new IllegalArgumentException("Stored batch transaction hash or ID is inconsistent");
            }
        } else {
            transaction = decodeGovernanceTransaction(
                    transactionId, eventId, transactionType, eventTime, payload, signatures);
            GovernanceTransaction governanceTransaction = (GovernanceTransaction) transaction;
            if (!payloadHash.equals(GovernanceCodec.payloadHash(networkId, governanceTransaction))
                    || !transactionId.equals(GovernanceCodec.transactionId(networkId, governanceTransaction))) {
                throw new IllegalArgumentException("Stored governance transaction hash or ID is inconsistent");
            }
        }
        return transaction;
    }

    private static BatchEvent decodeBatchEvent(
            String transactionId,
            String eventId,
            String transactionType,
            Instant eventTime,
            JsonObject payload,
            JsonArray signatures
    ) {
        List<SignatureEnvelope> decodedSignatures = new ArrayList<>();
        for (JsonElement element : signatures) {
            if (!element.isJsonObject()) {
                throw new IllegalArgumentException("Stored batch signature must be an object");
            }
            JsonObject signature = element.getAsJsonObject();
            decodedSignatures.add(new SignatureEnvelope(
                    requiredString(signature, "organizationId"),
                    requiredString(signature, "keyId"),
                    requiredString(signature, "purpose"),
                    requiredString(signature, "signature")));
        }
        JsonElement data = payload.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("Stored batch transaction data must be an object");
        }
        return new BatchEvent(
                transactionId,
                eventId,
                requiredString(payload, "batchCode"),
                EventType.valueOf(transactionType),
                eventTime,
                decodeObject(data.getAsJsonObject()),
                decodedSignatures);
    }

    private static GovernanceTransaction decodeGovernanceTransaction(
            String transactionId,
            String eventId,
            String transactionType,
            Instant eventTime,
            JsonObject payload,
            JsonArray signatures
    ) {
        if (signatures.size() != 1 || !signatures.get(0).isJsonObject()) {
            throw new IllegalArgumentException("Stored governance transaction must have one signature");
        }
        JsonObject signature = signatures.get(0).getAsJsonObject();
        if (!GovernanceCodec.ADMIN_SIGNING_PURPOSE.equals(requiredString(signature, "purpose"))) {
            throw new IllegalArgumentException("Stored governance signature has an invalid purpose");
        }
        JsonElement data = payload.get("data");
        if (data == null || !data.isJsonObject()) {
            throw new IllegalArgumentException("Stored governance transaction data must be an object");
        }
        Map<String, String> decodedData = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : data.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive()
                    || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IllegalArgumentException("Stored governance data values must be strings");
            }
            decodedData.put(entry.getKey(), entry.getValue().getAsString());
        }
        return new GovernanceTransaction(
                transactionId,
                eventId,
                GovernanceType.valueOf(transactionType),
                eventTime,
                decodedData,
                requiredString(signature, "signature"));
    }

    private static Map<String, Object> decodeObject(JsonObject object) {
        Map<String, Object> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            decoded.put(entry.getKey(), decodeValue(entry.getValue()));
        }
        return decoded;
    }

    private static Object decodeValue(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonObject()) {
            return decodeObject(element.getAsJsonObject());
        }
        if (element.isJsonArray()) {
            List<Object> values = new ArrayList<>();
            for (JsonElement value : element.getAsJsonArray()) {
                values.add(decodeValue(value));
            }
            return Collections.unmodifiableList(values);
        }
        if (element.isJsonPrimitive()) {
            if (element.getAsJsonPrimitive().isBoolean()) {
                return element.getAsBoolean();
            }
            if (element.getAsJsonPrimitive().isString()) {
                return element.getAsString();
            }
            return new BigDecimal(element.getAsString());
        }
        throw new IllegalArgumentException("Stored JSON value has an unsupported type");
    }

    private static boolean isBatchEventType(String transactionType) {
        try {
            EventType.valueOf(transactionType);
            return true;
        } catch (IllegalArgumentException exception) {
            try {
                GovernanceType.valueOf(transactionType);
                return false;
            } catch (IllegalArgumentException governanceException) {
                throw new IllegalArgumentException("Unknown stored transaction type", governanceException);
            }
        }
    }

    private static JsonObject parseObject(String json, String field) {
        JsonElement element = JsonParser.parseString(json);
        if (!element.isJsonObject()) {
            throw new IllegalArgumentException("Stored transaction " + field + " must be an object");
        }
        return element.getAsJsonObject();
    }

    private static String requiredString(JsonObject object, String key) {
        JsonElement element = object.get(key);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException("Stored transaction is missing string field " + key);
        }
        return element.getAsString();
    }
}
