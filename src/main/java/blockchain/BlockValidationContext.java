package blockchain;

import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.LedgerTransaction;

public record BlockValidationContext(
        Block parent,
        Set<String> ancestorTransactionIds,
        Set<String> ancestorEventIds,
        GovernanceRegistry governanceRegistry,
        Map<String, BatchSnapshot> batches,
        Map<String, BatchEvent> eventsByTransaction,
        Map<String, LedgerTransaction> transactionsById
) {
    public BlockValidationContext {
        ancestorTransactionIds = Set.copyOf(
                ancestorTransactionIds == null ? Set.of() : ancestorTransactionIds);
        ancestorEventIds = Set.copyOf(ancestorEventIds == null ? Set.of() : ancestorEventIds);
        governanceRegistry = governanceRegistry == null ? GovernanceRegistry.empty() : governanceRegistry;
        batches = Map.copyOf(batches == null ? Map.of() : batches);
        eventsByTransaction = Map.copyOf(
                eventsByTransaction == null ? Map.of() : eventsByTransaction);
        transactionsById = Map.copyOf(transactionsById == null ? Map.of() : transactionsById);
        for (Map.Entry<String, LedgerTransaction> entry : transactionsById.entrySet()) {
            String id = entry.getKey();
            LedgerTransaction transaction = entry.getValue();
            if (!id.equals(transaction.transactionId())) {
                throw new IllegalArgumentException("transaction map key does not match transaction ID");
            }
        }
        if (parent == null && (!ancestorTransactionIds.isEmpty()
                || !ancestorEventIds.isEmpty() || !batches.isEmpty()
                || !eventsByTransaction.isEmpty() || !transactionsById.isEmpty())) {
            throw new IllegalArgumentException("genesis validation context cannot contain ancestor state");
        }
    }

    public BlockValidationContext(
            Block parent,
            Set<String> ancestorTransactionIds,
            Set<String> ancestorEventIds,
            GovernanceRegistry governanceRegistry,
            Map<String, BatchSnapshot> batches,
            Map<String, BatchEvent> eventsByTransaction
    ) {
        this(parent, ancestorTransactionIds, ancestorEventIds, governanceRegistry,
                batches, eventsByTransaction, Map.of());
    }

    public BlockValidationContext(
            Block parent,
            Set<String> ancestorTransactionIds,
            Set<String> ancestorEventIds,
            Map<String, model.Organization> organizations,
            Map<String, model.OrganizationKey> organizationKeys,
            Map<String, BatchSnapshot> batches,
            Map<String, BatchEvent> eventsByTransaction
    ) {
        this(
                parent,
                ancestorTransactionIds,
                ancestorEventIds,
                GovernanceRegistry.from(organizations, organizationKeys),
                batches,
                eventsByTransaction,
                Map.of());
    }

    public static BlockValidationContext genesis(
            Map<String, model.Organization> organizations,
            Map<String, model.OrganizationKey> organizationKeys
    ) {
        return new BlockValidationContext(
                null,
                Set.of(),
                Set.of(),
                GovernanceRegistry.from(organizations, organizationKeys),
                Map.of(),
                Map.of(),
                Map.of());
    }

    public static BlockValidationContext genesis(GovernanceRegistry registry) {
        return new BlockValidationContext(null, Set.of(), Set.of(), registry, Map.of(), Map.of(), Map.of());
    }
}


