package blockchain;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.GovernanceTransaction;
import model.LedgerTransaction;

public final class BlockValidator {
    private final String networkId;
    private final String expectedGenesisHash;
    private final int expectedDifficulty;
    private final TransactionValidator transactionValidator;
    private final BusinessRuleValidator businessRuleValidator;
    private final GovernanceValidator governanceValidator;

    public BlockValidator(String networkId, int expectedDifficulty, String expectedGenesisHash) {
        this(networkId, expectedDifficulty, expectedGenesisHash, null);
    }

    public BlockValidator(
            String networkId,
            int expectedDifficulty,
            String expectedGenesisHash,
            byte[] genesisAdminPublicKey
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        ProofOfWork.requireDifficulty(expectedDifficulty);
        if (expectedGenesisHash == null || !expectedGenesisHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedGenesisHash must be a lowercase SHA-256 hex digest");
        }
        this.networkId = networkId;
        this.expectedGenesisHash = expectedGenesisHash;
        this.expectedDifficulty = expectedDifficulty;
        transactionValidator = new TransactionValidator(networkId);
        businessRuleValidator = new BusinessRuleValidator();
        governanceValidator = genesisAdminPublicKey == null
                ? null : new GovernanceValidator(networkId, genesisAdminPublicKey);
    }

    public BlockValidationResult validate(
            Block block,
            List<? extends LedgerTransaction> transactions,
            BlockValidationContext context
    ) {
        if (block == null || context == null) {
            throw invalid("INVALID_BLOCK", "Block and validation context are required");
        }
        if (!networkId.equals(block.header().networkId())) {
            throw invalid("WRONG_NETWORK", "Block belongs to a different network");
        }
        if (block.header().difficulty() != expectedDifficulty) {
            throw invalid("INVALID_DIFFICULTY", "Block difficulty differs from the network setting");
        }
        if (!block.hash().equals(BlockCodec.hashHeader(block.header()))) {
            throw invalid("INVALID_BLOCK_HASH", "Block hash does not match its canonical header");
        }
        if (!block.header().transactionsHash().equals(BlockCodec.transactionsHash(block.transactionIds()))) {
            throw invalid("INVALID_TRANSACTIONS_HASH", "Block transaction commitment does not match its body");
        }
        if (!ProofOfWork.hasValidProof(block)) {
            throw invalid("INVALID_PROOF_OF_WORK", "Block hash does not meet its proof-of-work target");
        }
        validateLinkage(block, context.parent());
        if (context.parent() == null && !expectedGenesisHash.equals(block.hash())) {
            throw invalid("WRONG_GENESIS", "Block does not match the configured network genesis hash");
        }

        BigInteger expectedCumulativeWork = context.parent() == null
                ? BigInteger.ZERO
                : context.parent().cumulativeWork();
        expectedCumulativeWork = expectedCumulativeWork.add(
                ProofOfWork.workForDifficulty(expectedDifficulty));
        if (!expectedCumulativeWork.equals(block.cumulativeWork())) {
            throw invalid("INVALID_CUMULATIVE_WORK", "Block cumulative work is incorrect");
        }

        List<LedgerTransaction> blockTransactions = transactions == null
                ? List.of() : new ArrayList<>(transactions);
        if (blockTransactions.stream().anyMatch(transaction -> transaction == null)) {
            throw invalid("BLOCK_TRANSACTION_MISMATCH", "Block transaction list must not contain null entries");
        }
        if (blockTransactions.size() != block.transactionIds().size()) {
            throw invalid("BLOCK_TRANSACTION_MISMATCH", "Block transaction list does not match its body");
        }
        ReplayState replay = replayTransactions(
                block.header().height(), blockTransactions, context, block.transactionIds());
        return new BlockValidationResult(
                block,
                replay.governanceRegistry(),
                replay.batches(),
                replay.eventsByTransaction(),
                replay.transactionsById(),
                replay.transactionIds(),
                replay.eventIds());
    }

    public void validateTransactions(
            long inclusionHeight,
            List<? extends LedgerTransaction> transactions,
            BlockValidationContext context
    ) {
        if (context == null || inclusionHeight < 0) {
            throw invalid("INVALID_VALIDATION_CONTEXT", "Context and non-negative inclusion height are required");
        }
        List<LedgerTransaction> candidates = transactions == null
                ? List.of() : new ArrayList<>(transactions);
        if (candidates.stream().anyMatch(transaction -> transaction == null)) {
            throw invalid("BLOCK_TRANSACTION_MISMATCH", "Transaction list must not contain null entries");
        }
        replayTransactions(inclusionHeight, candidates, context, null);
    }

    private ReplayState replayTransactions(
            long inclusionHeight,
            List<? extends LedgerTransaction> blockTransactions,
            BlockValidationContext context,
            List<String> expectedTransactionIds
    ) {
        Map<String, BatchSnapshot> batches = new HashMap<>(context.batches());
        Map<String, BatchEvent> events = new HashMap<>(context.eventsByTransaction());
        Map<String, LedgerTransaction> transactionsById = new HashMap<>(context.transactionsById());
        GovernanceRegistry governanceRegistry = context.governanceRegistry();
        Set<String> transactionIds = new HashSet<>(context.ancestorTransactionIds());
        Set<String> eventIds = new HashSet<>(context.ancestorEventIds());

        for (int index = 0; index < blockTransactions.size(); index++) {
            LedgerTransaction transaction = blockTransactions.get(index);
            if (expectedTransactionIds != null
                    && !expectedTransactionIds.get(index).equals(transaction.transactionId())) {
                throw invalid("BLOCK_TRANSACTION_MISMATCH", "Block transaction order or ID is incorrect");
            }
            if (!transactionIds.add(transaction.transactionId())) {
                throw invalid("DUPLICATE_TRANSACTION", "Transaction already appears in the parent chain");
            }
            if (!eventIds.add(transaction.eventId())) {
                throw invalid("DUPLICATE_EVENT", "Event ID already appears in the parent chain");
            }
            transactionsById.put(transaction.transactionId(), transaction);
            if (transaction instanceof BatchEvent batchEvent) {
                validateBatchEvent(batchEvent, inclusionHeight, governanceRegistry, batches, events);
            } else if (transaction instanceof GovernanceTransaction governanceTransaction) {
                if (governanceValidator == null) {
                    throw invalid(
                            "GOVERNANCE_VALIDATOR_NOT_CONFIGURED",
                            "Genesis administrator key is required to validate governance transactions");
                }
                try {
                    governanceRegistry = governanceValidator.validateAndApply(
                            governanceTransaction, inclusionHeight, governanceRegistry);
                } catch (GovernanceValidationException exception) {
                    throw new BlockValidationException(
                            exception.getCode(),
                            "Block contains invalid governance: " + exception.getMessage(),
                            exception);
                }
            } else {
                throw invalid("UNSUPPORTED_TRANSACTION_TYPE", "Block contains an unsupported transaction type");
            }
        }

        return new ReplayState(
                governanceRegistry, batches, events, transactionsById, transactionIds, eventIds);
    }

    private void validateBatchEvent(
            BatchEvent transaction,
            long inclusionHeight,
            GovernanceRegistry governanceRegistry,
            Map<String, BatchSnapshot> batches,
            Map<String, BatchEvent> events
    ) {
        try {
            transactionValidator.validate(
                    transaction,
                    inclusionHeight,
                    governanceRegistry.organizationValidationView(),
                    governanceRegistry.organizationKeys());
                BatchSnapshot updated = businessRuleValidator.apply(
                        batches.get(transaction.batchCode()),
                        transaction,
                        governanceRegistry.organizationValidationView(),
                        events);
                batches.put(transaction.batchCode(), updated);
                events.put(transaction.transactionId(), transaction);
        } catch (TransactionValidationException | BusinessRuleException exception) {
            throw new BlockValidationException(
                    exception instanceof TransactionValidationException validationException
                            ? validationException.getCode() : ((BusinessRuleException) exception).getCode(),
                    "Block contains an invalid transaction: " + exception.getMessage(),
                    exception);
        }
    }

    private void validateLinkage(Block block, Block parent) {
        if (parent != null && parent.header().height() == Long.MAX_VALUE) {
            throw invalid("CHAIN_HEIGHT_OVERFLOW", "Parent height cannot be incremented");
        }
        long expectedHeight = parent == null ? 0 : parent.header().height() + 1;
        String expectedPreviousHash = parent == null ? null : parent.hash();
        Instant parentTime = parent == null ? null : parent.header().timestamp();
        if (block.header().height() != expectedHeight
                || !java.util.Objects.equals(block.header().previousHash(), expectedPreviousHash)) {
            throw invalid("INVALID_BLOCK_LINK", "Block height or previous hash does not match its parent");
        }
        if (parentTime != null && !block.header().timestamp().isAfter(parentTime)) {
            throw invalid("INVALID_BLOCK_TIME", "Block timestamp must be later than its parent");
        }
        if (block.header().height() == 0 && !block.transactionIds().isEmpty()) {
            throw invalid("INVALID_GENESIS_BLOCK", "Genesis block must not contain transactions");
        }
        if (parent != null && (!networkId.equals(parent.header().networkId())
                || parent.header().difficulty() != expectedDifficulty
                || !parent.hash().equals(BlockCodec.hashHeader(parent.header()))
                || !ProofOfWork.hasValidProof(parent)
                || !parent.header().transactionsHash().equals(BlockCodec.transactionsHash(parent.transactionIds())))) {
            throw invalid("INVALID_PARENT_BLOCK", "Parent block is not structurally valid for this network");
        }
    }

    private BlockValidationException invalid(String code, String message) {
        return new BlockValidationException(code, message);
    }

    private record ReplayState(
            GovernanceRegistry governanceRegistry,
            Map<String, BatchSnapshot> batches,
            Map<String, BatchEvent> eventsByTransaction,
            Map<String, LedgerTransaction> transactionsById,
            Set<String> transactionIds,
            Set<String> eventIds
    ) {
    }
}
