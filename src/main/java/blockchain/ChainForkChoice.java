package blockchain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import model.Block;

public final class ChainForkChoice {
    private static final Comparator<Block> PREFERENCE =
            Comparator.comparing(Block::cumulativeWork)
                    .thenComparing(Block::hash, Comparator.reverseOrder());

    private ChainForkChoice() {
    }

    public static Block selectCanonicalTip(Collection<Block> validTips) {
        if (validTips == null || validTips.isEmpty()) {
            throw new IllegalArgumentException("validTips must contain at least one block");
        }
        List<Block> candidates = new ArrayList<>(validTips);
        if (candidates.stream().anyMatch(block -> block == null)) {
            throw new IllegalArgumentException("validTips must not contain null blocks");
        }
        return candidates.stream().max(PREFERENCE).orElseThrow();
    }

    public static boolean isPreferred(Block candidate, Block currentTip) {
        if (candidate == null || currentTip == null) {
            throw new IllegalArgumentException("candidate and currentTip must not be null");
        }
        return isPreferred(
                candidate.cumulativeWork(),
                candidate.hash(),
                currentTip.cumulativeWork(),
                currentTip.hash());
    }

    public static boolean isPreferred(
            java.math.BigInteger candidateWork,
            String candidateHash,
            java.math.BigInteger currentWork,
            String currentHash
    ) {
        if (candidateWork == null || currentWork == null
                || candidateHash == null || currentHash == null) {
            throw new IllegalArgumentException("work and hashes must not be null");
        }
        int workComparison = candidateWork.compareTo(currentWork);
        return workComparison > 0 || (workComparison == 0 && candidateHash.compareTo(currentHash) < 0);
    }
}
