package network;

import blockchain.GovernanceCodec;
import blockchain.LedgerTransactionCodec;
import blockchain.TransactionCodec;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Set;
import model.BatchEvent;
import model.GovernanceTransaction;
import model.LedgerTransaction;

public final class LedgerTransactionWireCodec {
    private static final com.google.gson.Gson GSON = new com.google.gson.Gson();
    private static final Set<String> FIELDS = Set.of(
            "transactionId", "eventId", "transactionType", "payload", "payloadHash", "signatures");

    private LedgerTransactionWireCodec() {
    }

    public static JsonObject encode(String networkId, LedgerTransaction transaction) {
        if (networkId == null || networkId.isBlank() || transaction == null) {
            throw new IllegalArgumentException("networkId and transaction are required");
        }
        String payload;
        String payloadHash;
        String signatures;
        if (transaction instanceof BatchEvent event) {
            payload = TransactionCodec.payloadJson(networkId, event);
            payloadHash = TransactionCodec.payloadHash(networkId, event);
            if (!transaction.transactionId().equals(TransactionCodec.transactionId(networkId, event))) {
                throw new IllegalArgumentException("Transaction ID does not match its canonical payload");
            }
            signatures = TransactionCodec.signaturesJson(event);
        } else if (transaction instanceof GovernanceTransaction governance) {
            payload = GovernanceCodec.payloadJson(networkId, governance);
            payloadHash = GovernanceCodec.payloadHash(networkId, governance);
            if (!transaction.transactionId().equals(
                    GovernanceCodec.transactionId(networkId, governance))) {
                throw new IllegalArgumentException("Transaction ID does not match its canonical payload");
            }
            signatures = GovernanceCodec.signaturesJson(governance);
        } else {
            throw new IllegalArgumentException("Unsupported ledger transaction type");
        }
        JsonObject wire = new JsonObject();
        wire.addProperty("transactionId", transaction.transactionId());
        wire.addProperty("eventId", transaction.eventId());
        wire.addProperty("transactionType", transaction.transactionType());
        wire.add("payload", JsonParser.parseString(payload));
        wire.addProperty("payloadHash", payloadHash);
        wire.add("signatures", JsonParser.parseString(signatures));
        return wire;
    }

    public static LedgerTransaction decode(String networkId, JsonElement wireElement) {
        if (networkId == null || networkId.isBlank()
                || wireElement == null || !wireElement.isJsonObject()) {
            throw new IllegalArgumentException("Ledger transaction must be a JSON object");
        }
        JsonObject wire = wireElement.getAsJsonObject();
        if (!FIELDS.equals(wire.keySet())) {
            throw new IllegalArgumentException("Ledger transaction contains missing or unsupported fields");
        }
        String transactionId = requiredString(wire, "transactionId");
        String eventId = requiredString(wire, "eventId");
        String type = requiredString(wire, "transactionType");
        String payloadHash = requiredString(wire, "payloadHash");
        JsonElement payload = wire.get("payload");
        JsonElement signatures = wire.get("signatures");
        if (payload == null || !payload.isJsonObject()
                || signatures == null || !signatures.isJsonArray()) {
            throw new IllegalArgumentException("Ledger transaction payload and signatures are required");
        }
        try {
            return LedgerTransactionCodec.decode(
                    networkId,
                    transactionId,
                    eventId,
                    type,
                    GSON.toJson(payload),
                    payloadHash,
                    GSON.toJson(signatures));
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new IllegalArgumentException("Ledger transaction payload or signatures are malformed", exception);
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
}
