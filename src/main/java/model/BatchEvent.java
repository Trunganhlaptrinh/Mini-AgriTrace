package model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record BatchEvent(
        String transactionId,
        String eventId,
        String batchCode,
        EventType eventType,
        Instant eventTime,
        Map<String, Object> data,
        List<SignatureEnvelope> signatures
) implements LedgerTransaction {
    public BatchEvent {
        requireText(transactionId, "transactionId");
        requireText(eventId, "eventId");
        requireMaxLength(eventId, 255, "eventId");
        requireText(batchCode, "batchCode");
        requireMaxLength(batchCode, 100, "batchCode");
        if (eventType == null) {
            throw new IllegalArgumentException("eventType must not be null");
        }
        if (eventTime == null) {
            throw new IllegalArgumentException("eventTime must not be null");
        }
        data = immutableObject(data == null ? Map.of() : data);
        signatures = List.copyOf(signatures == null ? List.of() : signatures);
    }

    @Override
    public String transactionType() {
        return eventType.name();
    }

    private static Map<String, Object> immutableObject(Map<String, ?> object) {
        Map<String, Object> copy = new LinkedHashMap<>();
        object.forEach((key, value) -> {
            requireText(key, "event data key");
            copy.put(key, immutableJsonValue(value));
        });
        return Collections.unmodifiableMap(copy);
    }

    private static Object immutableJsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof BigDecimal || value instanceof BigInteger
                || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Double number) {
            if (!Double.isFinite(number)) {
                throw new IllegalArgumentException("Event data numbers must be finite");
            }
            return number;
        }
        if (value instanceof Float number) {
            if (!Float.isFinite(number)) {
                throw new IllegalArgumentException("Event data numbers must be finite");
            }
            return number;
        }
        if (value instanceof Map<?, ?> nestedObject) {
            Map<String, Object> copy = new LinkedHashMap<>();
            nestedObject.forEach((key, nestedValue) -> {
                if (!(key instanceof String textKey)) {
                    throw new IllegalArgumentException("Event data object keys must be strings");
                }
                requireText(textKey, "event data key");
                copy.put(textKey, immutableJsonValue(nestedValue));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> nestedArray) {
            List<Object> copy = new ArrayList<>(nestedArray.size());
            nestedArray.forEach(item -> copy.add(immutableJsonValue(item)));
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("Event data must contain only JSON values");
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireMaxLength(String value, int maximum, String name) {
        if (value.length() > maximum) {
            throw new IllegalArgumentException(name + " must not exceed " + maximum + " characters");
        }
    }
}
