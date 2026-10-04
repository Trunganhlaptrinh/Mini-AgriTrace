package blockchain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import model.Block;
import model.BlockHeader;

public final class ProofOfWork {
    public static final int MAX_DIFFICULTY = BlockHeader.MAX_DIFFICULTY;

    private ProofOfWork() {
    }

    public static Block mine(
            String networkId,
            long height,
            String previousHash,
            Instant timestamp,
            int difficulty,
            List<String> transactionIds,
            BigInteger parentCumulativeWork
    ) {
        requireDifficulty(difficulty);
        if (parentCumulativeWork == null || parentCumulativeWork.signum() < 0) {
            throw new IllegalArgumentException("parentCumulativeWork must not be null or negative");
        }
        List<String> orderedTransactionIds = new ArrayList<>(transactionIds == null
                ? List.of() : transactionIds);
        orderedTransactionIds.sort(String::compareTo);
        String transactionsHash = BlockCodec.transactionsHash(orderedTransactionIds);
        BigInteger cumulativeWork = parentCumulativeWork.add(workForDifficulty(difficulty));

        for (long nonce = 0; ; nonce++) {
            BlockHeader header = new BlockHeader(
                    networkId,
                    height,
                    previousHash,
                    timestamp,
                    nonce,
                    difficulty,
                    transactionsHash);
            Block candidate = new Block(
                    header,
                    orderedTransactionIds,
                    cumulativeWork,
                    BlockCodec.hashHeader(header));
            if (hasValidProof(candidate)) {
                return candidate;
            }
            if (nonce == Long.MAX_VALUE) {
                throw new IllegalStateException("Proof-of-work nonce space exhausted");
            }
        }
    }

    public static boolean hasValidProof(Block block) {
        if (block == null) {
            return false;
        }
        String recomputedHash = BlockCodec.hashHeader(block.header());
        return recomputedHash.equals(block.hash())
                && hasValidHash(recomputedHash, block.header().difficulty());
    }

    public static boolean hasValidHash(String hash, int difficulty) {
        requireDifficulty(difficulty);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            return false;
        }
        return hash.startsWith("0".repeat(difficulty));
    }

    public static BigInteger workForDifficulty(int difficulty) {
        requireDifficulty(difficulty);
        return BigInteger.ONE.shiftLeft(difficulty * 4);
    }

    public static void requireDifficulty(int difficulty) {
        if (difficulty < 1 || difficulty > MAX_DIFFICULTY) {
            throw new IllegalArgumentException(
                    "difficulty must be between 1 and " + MAX_DIFFICULTY);
        }
    }
}
