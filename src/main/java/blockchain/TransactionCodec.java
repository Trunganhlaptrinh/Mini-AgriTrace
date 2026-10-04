package blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.SignatureEnvelope;

public final class TransactionCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter EVENT_TIME_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();
    private static final Comparator<SignatureEnvelope> SIGNATURE_ORDER =
            Comparator.comparing(SignatureEnvelope::organizationId)
                    .thenComparing(SignatureEnvelope::purpose)
                    .thenComparing(SignatureEnvelope::keyId)
                    .thenComparing(SignatureEnvelope::signature);

    private TransactionCodec() {
    }

    public static byte[] signingBytes(
            String networkId,
            BatchEvent event,
            SignatureEnvelope signature
    ) {
        requireNetworkId(networkId);
        if (event == null || signature == null) {
            throw new IllegalArgumentException("event and signature must not be null");
        }
        Map<String, Object> envelope = payloadFields(networkId, event);
        envelope.put("purpose", signature.purpose());
        envelope.put("signerOrganizationId", signature.organizationId());
        envelope.put("keyId", signature.keyId());
        return canonicalBytes(envelope);
    }

    public static String payloadHash(String networkId, BatchEvent event) {
        requireNetworkId(networkId);
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return HashUtil.sha256Hex(payloadJson(networkId, event).getBytes(StandardCharsets.UTF_8));
    }

    public static String payloadJson(String networkId, BatchEvent event) {
        requireNetworkId(networkId);
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return canonicalJson(payloadFields(networkId, event));
    }

    public static String signaturesJson(BatchEvent event) {
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        return canonicalJson(sortedSignatures(event));
    }

    private static List<Map<String, String>> sortedSignatures(BatchEvent event) {
        List<Map<String, String>> signatures = new ArrayList<>();
        event.signatures().stream()
                .sorted(SIGNATURE_ORDER)
                .forEach(signature -> {
                    Map<String, String> encoded = new LinkedHashMap<>();
                    encoded.put("organizationId", signature.organizationId());
                    encoded.put("keyId", signature.keyId());
                    encoded.put("purpose", signature.purpose());
                    encoded.put("signature", signature.signature());
                    signatures.add(encoded);
                });
        return signatures;
    }

    public static String transactionId(String networkId, BatchEvent event) {
        requireNetworkId(networkId);
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        String payloadHash = payloadHash(networkId, event);
        Map<String, Object> transaction = payloadFields(networkId, event);
        transaction.put("payloadHash", payloadHash);
        transaction.put("signatures", sortedSignatures(event));
        return HashUtil.sha256Hex(canonicalBytes(transaction));
    }

    private static Map<String, Object> payloadFields(String networkId, BatchEvent event) {
        if (event.eventTime().getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("eventTime precision must not exceed milliseconds");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("networkId", networkId);
        payload.put("eventId", event.eventId());
        payload.put("batchCode", event.batchCode());
        payload.put("eventType", event.eventType().name());
        payload.put("eventTime", EVENT_TIME_FORMATTER.format(event.eventTime()));
        payload.put("data", event.data());
        return payload;
    }

    private static byte[] canonicalBytes(Map<String, ?> value) {
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
}
