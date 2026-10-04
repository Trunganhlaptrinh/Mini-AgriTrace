package blockchain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import model.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChainForkChoiceTest {
    @Test
    void selectsTheTipWithTheGreatestCumulativeWork() {
        Block lowWork = mine(1, Instant.parse("2026-10-04T10:01:00Z"), 1);
        Block highWork = mine(2, Instant.parse("2026-10-04T10:02:00Z"), 2);

        assertEquals(highWork, ChainForkChoice.selectCanonicalTip(List.of(lowWork, highWork)));
        assertTrue(ChainForkChoice.isPreferred(highWork, lowWork));
    }

    @Test
    void breaksEqualWorkTiesWithLexicographicallySmallerHash() {
        Block first = mine(1, Instant.parse("2026-10-04T10:01:00Z"), 1);
        Block second = mine(1, Instant.parse("2026-10-04T10:02:00Z"), 1);
        Block expected = first.hash().compareTo(second.hash()) < 0 ? first : second;
        Block other = expected == first ? second : first;

        assertEquals(expected, ChainForkChoice.selectCanonicalTip(List.of(first, second)));
        assertTrue(ChainForkChoice.isPreferred(expected, other));
        assertFalse(ChainForkChoice.isPreferred(other, expected));
    }

    @Test
    void rejectsAnEmptyCandidateSet() {
        assertThrows(IllegalArgumentException.class,
                () -> ChainForkChoice.selectCanonicalTip(List.of()));
    }

    @Test
    void rejectsNullCandidatesInsteadOfSilentlyIgnoringThem() {
        Block block = mine(1, Instant.parse("2026-10-04T10:01:00Z"), 1);

        assertThrows(IllegalArgumentException.class,
                () -> ChainForkChoice.selectCanonicalTip(java.util.Arrays.asList(block, null)));
    }

    private Block mine(long height, Instant time, int difficulty) {
        return ProofOfWork.mine(
                "agritrace-test",
                height,
                "a".repeat(64),
                time,
                difficulty,
                List.of(),
                BigInteger.ZERO);
    }
}
