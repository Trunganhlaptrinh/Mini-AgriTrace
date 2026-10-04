package model;

import java.time.Instant;

public record BlockHeader(
        String networkId,
        long height,
        String previousHash,
        Instant timestamp,
        long nonce,
        int difficulty,
        String transactionsHash
) {
    public static final int MAX_DIFFICULTY = 16;

    public BlockHeader {
        requireText(networkId, "networkId");
        if (height < 0) {
            throw new IllegalArgumentException("height must not be negative");
        }
        if (height == 0 && previousHash != null) {
            throw new IllegalArgumentException("genesis block cannot have a previous hash");
        }
        if (height > 0) {
            requireHash(previousHash, "previousHash");
        }
        if (timestamp == null || timestamp.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("timestamp must have millisecond precision");
        }
        if (nonce < 0) {
            throw new IllegalArgumentException("nonce must not be negative");
        }
        if (difficulty < 1 || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException("difficulty must be between 1 and " + MAX_DIFFICULTY);
        }
        requireHash(transactionsHash, "transactionsHash");
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
