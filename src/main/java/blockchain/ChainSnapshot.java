package blockchain;

import model.Block;

public record ChainSnapshot(
        Block tip,
        BlockValidationContext nextBlockContext,
        long blocksValidated
) {
    public ChainSnapshot {
        if (nextBlockContext == null || blocksValidated < 0) {
            throw new IllegalArgumentException("validation context and non-negative block count are required");
        }
        if (blocksValidated == 0) {
            if (tip != null || nextBlockContext.parent() != null) {
                throw new IllegalArgumentException("An empty chain cannot have a tip or parent context");
            }
        } else if (tip == null || blocksValidated != tip.header().height() + 1
                || nextBlockContext.parent() == null
                || !tip.hash().equals(nextBlockContext.parent().hash())) {
            throw new IllegalArgumentException("Chain snapshot tip and replay context are inconsistent");
        }
    }
}
