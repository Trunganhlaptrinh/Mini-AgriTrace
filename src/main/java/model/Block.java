package model;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

public record Block(
        BlockHeader header,
        List<String> transactionIds,
        BigInteger cumulativeWork,
        String hash
) {
    public Block {
        if (header == null) {
            throw new IllegalArgumentException("header must not be null");
        }
        List<String> suppliedIds = transactionIds == null
                ? List.of() : new ArrayList<>(transactionIds);
        if (suppliedIds.stream().anyMatch(id -> id == null)) {
            throw new IllegalArgumentException("transactionIds must not contain null");
        }
        transactionIds = List.copyOf(suppliedIds);
        if (transactionIds.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("transaction IDs must be lowercase SHA-256 hex digests");
        }
        List<String> sortedIds = new ArrayList<>(transactionIds);
        sortedIds.sort(String::compareTo);
        if (!sortedIds.equals(transactionIds)) {
            throw new IllegalArgumentException("transactionIds must be sorted lexicographically");
        }
        if (transactionIds.stream().distinct().count() != transactionIds.size()) {
            throw new IllegalArgumentException("transactionIds must not contain duplicates");
        }
        if (cumulativeWork == null || cumulativeWork.signum() < 0) {
            throw new IllegalArgumentException("cumulativeWork must not be null or negative");
        }
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("hash must be a lowercase SHA-256 hex digest");
        }
    }
}
