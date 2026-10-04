package model;

import java.time.Instant;

public record TransactionStatusSnapshot(
        String transactionId,
        String eventId,
        String transactionType,
        String payloadHash,
        TransactionStatus status,
        String rejectionCode,
        Instant updatedAt,
        Long blockHeight,
        String blockHash,
        Instant blockTimestamp
) {
    public TransactionStatusSnapshot {
        requireHash(transactionId, "transactionId");
        requireText(eventId, "eventId");
        requireText(transactionType, "transactionType");
        requireHash(payloadHash, "payloadHash");
        if (status == null) {
            throw new IllegalArgumentException("status must not be null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt must not be null");
        }
        if (status == TransactionStatus.CONFIRMED
                && (blockHeight == null || blockHash == null || blockTimestamp == null)) {
            throw new IllegalArgumentException("confirmed transactions must include canonical block metadata");
        }
        if (status != TransactionStatus.CONFIRMED
                && (blockHeight != null || blockHash != null || blockTimestamp != null)) {
            throw new IllegalArgumentException("only confirmed transactions may include block metadata");
        }
        if (status == TransactionStatus.REJECTED
                && (rejectionCode == null || rejectionCode.isBlank() || rejectionCode.length() > 80)) {
            throw new IllegalArgumentException("rejected transactions must include a valid rejection code");
        }
        if (status != TransactionStatus.REJECTED && rejectionCode != null) {
            throw new IllegalArgumentException("only rejected transactions may include a rejection code");
        }
        if (blockHeight != null && blockHeight < 0) {
            throw new IllegalArgumentException("blockHeight must not be negative");
        }
        if (blockHash != null) {
            requireHash(blockHash, "blockHash");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    private static void requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex digest");
        }
    }
}
