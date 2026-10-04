package blockchain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import model.Block;
import model.BlockHeader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockCodecTest {
    private static final Instant TIMESTAMP = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void transactionCommitmentIsIndependentOfInputOrder() {
        String first = "a".repeat(64);
        String second = "b".repeat(64);

        assertEquals(BlockCodec.transactionsHash(List.of(first, second)),
                BlockCodec.transactionsHash(List.of(second, first)));
    }

    @Test
    void canonicalizesHeaderWithProtocolFieldNamesAndMillisecondUtcTime() {
        BlockHeader header = header(0, null, 0, 1, BlockCodec.transactionsHash(List.of()));

        assertEquals(
                "{\"difficulty\":1,\"height\":0,\"networkId\":\"agritrace-test\",\"nonce\":0,"
                        + "\"previousHash\":null,\"timestamp\":\"2026-10-04T10:00:00.000Z\","
                        + "\"transactionsHash\":\"" + BlockCodec.transactionsHash(List.of()) + "\"}",
                BlockCodec.canonicalHeaderJson(header));
        assertEquals(
                "b9a280afe348e0d1c7496afb5682eb38a5403a964552169acd90c8e5bbca0bf8",
                BlockCodec.hashHeader(header));
    }

    @Test
    void proofOfWorkUsesRequiredLeadingZeroHexCharacters() {
        Block mined = ProofOfWork.mine(
                "agritrace-test", 0, null, TIMESTAMP, 2, List.of(), BigInteger.ZERO);

        assertTrue(ProofOfWork.hasValidProof(mined));
        assertTrue(mined.hash().startsWith("00"));
        assertEquals(ProofOfWork.workForDifficulty(2), mined.cumulativeWork());
        assertFalse(ProofOfWork.hasValidHash("z".repeat(64), 1));
    }

    @Test
    void enforcesDifficultyBounds() {
        assertThrows(IllegalArgumentException.class, () -> ProofOfWork.workForDifficulty(0));
        assertThrows(IllegalArgumentException.class,
                () -> ProofOfWork.workForDifficulty(ProofOfWork.MAX_DIFFICULTY + 1));
    }

    @Test
    void rejectsHeadersWithSubMillisecondTimestamp() {
        assertThrows(IllegalArgumentException.class, () -> header(
                0,
                null,
                0,
                1,
                Instant.parse("2026-10-04T10:00:00.000000001Z"),
                BlockCodec.transactionsHash(List.of())));
    }

    private BlockHeader header(
            long height,
            String previousHash,
            long nonce,
            int difficulty,
            String transactionsHash
    ) {
        return header(height, previousHash, nonce, difficulty, TIMESTAMP, transactionsHash);
    }

    private BlockHeader header(
            long height,
            String previousHash,
            long nonce,
            int difficulty,
            Instant timestamp,
            String transactionsHash
    ) {
        return new BlockHeader(
                "agritrace-test", height, previousHash, timestamp, nonce, difficulty, transactionsHash);
    }
}
