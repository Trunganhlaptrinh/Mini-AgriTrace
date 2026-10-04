package blockchain;

import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.LedgerTransaction;

public record BlockValidationResult(
        Block block,
        GovernanceRegistry governanceRegistry,
        Map<String, BatchSnapshot> batches,
        Map<String, BatchEvent> eventsByTransaction,
        Map<String, LedgerTransaction> transactionsById,
        Set<String> transactionIds,
        Set<String> eventIds
) {
    public BlockValidationResult {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }
        if (governanceRegistry == null) {
            throw new IllegalArgumentException("governanceRegistry must not be null");
        }
        batches = Map.copyOf(batches);
        eventsByTransaction = Map.copyOf(eventsByTransaction);
        transactionsById = Map.copyOf(transactionsById);
        for (Map.Entry<String, LedgerTransaction> entry : transactionsById.entrySet()) {
            String id = entry.getKey();
            LedgerTransaction transaction = entry.getValue();
            if (!id.equals(transaction.transactionId())) {
                throw new IllegalArgumentException("transaction map key does not match transaction ID");
            }
        }
        transactionIds = Set.copyOf(transactionIds);
        eventIds = Set.copyOf(eventIds);
    }

    public BlockValidationContext childContext() {
        return new BlockValidationContext(
                block,
                transactionIds,
                eventIds,
                governanceRegistry,
                batches,
                eventsByTransaction,
                transactionsById);
    }
}
