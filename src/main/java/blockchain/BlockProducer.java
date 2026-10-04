package blockchain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import model.Block;
import model.LedgerTransaction;

public final class BlockProducer {
    private final String networkId;
    private final int difficulty;
    private final Clock clock;
    private final Blockchain blockchain;
    private final PendingTransactionSource transactionSource;

    public BlockProducer(
            String networkId,
            int difficulty,
            Clock clock,
            Blockchain blockchain,
            PendingTransactionSource transactionSource
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        ProofOfWork.requireDifficulty(difficulty);
        this.networkId = networkId;
        this.difficulty = difficulty;
        this.clock = Objects.requireNonNull(clock, "clock");
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.transactionSource = Objects.requireNonNull(transactionSource, "transactionSource");
    }

    public Optional<BlockProcessingResult> produceNextBlock() {
        PendingTransactionSource.Selection selection = transactionSource.selectForNextBlock();
        if (selection.selected().isEmpty()) {
            return Optional.empty();
        }

        ChainSnapshot state = blockchain.loadCanonicalState();
        Block parent = state.tip();
        if (parent == null) {
            throw new IllegalStateException("Genesis must be initialized before producing child blocks");
        }
        long height = Math.addExact(parent.header().height(), 1);
        Instant timestamp = nextTimestamp(parent, clock.instant());
        List<LedgerTransaction> transactions = selection.selected();
        Block block = ProofOfWork.mine(
                networkId,
                height,
                parent.hash(),
                timestamp,
                difficulty,
                transactions.stream().map(LedgerTransaction::transactionId).toList(),
                parent.cumulativeWork());
        return Optional.of(blockchain.processBlock(block, transactions));
    }

    private Instant nextTimestamp(Block parent, Instant now) {
        Instant minimum = parent.header().timestamp().plusMillis(1);
        return now.isAfter(minimum) ? now : minimum;
    }
}
