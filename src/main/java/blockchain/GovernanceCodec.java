package blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.GovernanceTransaction;

public final class GovernanceCodec {
    public static final String ADMIN_SIGNING_PURPOSE = "GENESIS_ADMIN_GOVERNANCE";
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private GovernanceCodec() {
    }

    public static byte[] signingBytes(String networkId, GovernanceTransaction transaction) {
        requireNetworkId(networkId);
        requireTransaction(transaction);
        Map<String, Object> envelope = payloadFields(networkId, transaction);
        envelope.put("purpose", ADMIN_SIGNING_PURPOSE);
        return canonicalBytes(envelope);
    }

    public static String payloadJson(String networkId, GovernanceTransaction transaction) {
        requireNetworkId(networkId);
        requireTransaction(transaction);
        return canonicalJson(payloadFields(networkId, transaction));
    }

    public static String payloadHash(String networkId, GovernanceTransaction transaction) {
        return HashUtil.sha256Hex(payloadJson(networkId, transaction).getBytes(StandardCharsets.UTF_8));
    }

    public static String signaturesJson(GovernanceTransaction transaction) {
        requireTransaction(transaction);
        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("purpose", ADMIN_SIGNING_PURPOSE);
        signature.put("signature", transaction.adminSignature());
        return canonicalJson(List.of(signature));
    }

    public static String transactionId(String networkId, GovernanceTransaction transaction) {
        requireNetworkId(networkId);
        requireTransaction(transaction);
        Map<String, Object> envelope = payloadFields(networkId, transaction);
        envelope.put("payloadHash", payloadHash(networkId, transaction));
        envelope.put("signatures", List.of(Map.of(
                "purpose", ADMIN_SIGNING_PURPOSE,
                "signature", transaction.adminSignature())));
        return HashUtil.sha256Hex(canonicalBytes(envelope));
    }

    private static Map<String, Object> payloadFields(String networkId, GovernanceTransaction transaction) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("networkId", networkId);
        fields.put("eventId", transaction.eventId());
        fields.put("eventType", transaction.governanceType().name());
        fields.put("eventTime", EVENT_TIME_FORMATTER.format(transaction.eventTime()));
        fields.put("data", transaction.data());
        return fields;
    }

    private static byte[] canonicalBytes(Object value) {
        return canonicalJson(value).getBytes(StandardCharsets.UTF_8);
    }

    private static String canonicalJson(Object value) {
        return CanonicalJson.canonicalize(GSON.toJson(value));
    }

    private static void requireNetworkId(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
    }

    private static void requireTransaction(GovernanceTransaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
    }
}
