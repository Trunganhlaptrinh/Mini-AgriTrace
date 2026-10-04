package model;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

public record GovernanceTransaction(
        String transactionId,
        String eventId,
        GovernanceType governanceType,
        Instant eventTime,
        Map<String, String> data,
        String adminSignature
) implements LedgerTransaction {
    public GovernanceTransaction {
        requireText(transactionId, "transactionId");
        requireText(eventId, "eventId");
        if (eventId.length() > 255) {
            throw new IllegalArgumentException("eventId must not exceed 255 characters");
        }
        if (governanceType == null) {
            throw new IllegalArgumentException("governanceType must not be null");
        }
        if (eventTime == null || eventTime.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("eventTime must have millisecond precision");
        }
        data = Map.copyOf(data == null ? Map.of() : data);
        requireText(adminSignature, "adminSignature");
        try {
            if (Base64.getDecoder().decode(adminSignature).length != 64) {
                throw new IllegalArgumentException("P-256 signature must contain exactly 64 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("adminSignature must be Base64-encoded P-256 bytes", exception);
        }
    }

    @Override
    public String transactionType() {
        return governanceType.name();
    }

    public byte[] adminSignatureBytes() {
        return Base64.getDecoder().decode(adminSignature);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
