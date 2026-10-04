package blockchain;

public record ValidatedTransaction(String transactionId, String payloadHash) {
    public ValidatedTransaction {
        requireHash(transactionId, "transactionId");
        requireHash(payloadHash, "payloadHash");
    }

    private static void requireHash(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a lowercase SHA-256 hex digest");
        }
    }
}
