package blockchain;

import java.util.List;
import java.util.Objects;
import model.Block;
import model.LedgerTransaction;

public final class Blockchain {
    private final BlockValidator validator;
    private final BlockRepository repository;
    private final BlockValidationContext genesisContext;

    public Blockchain(
            BlockValidator validator,
            BlockRepository repository,
            BlockValidationContext genesisContext
    ) {
        this.validator = Objects.requireNonNull(validator, "validator");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.genesisContext = Objects.requireNonNull(genesisContext, "genesisContext");
        if (genesisContext.parent() != null
                || !genesisContext.ancestorTransactionIds().isEmpty()
                || !genesisContext.ancestorEventIds().isEmpty()
                || !genesisContext.transactionsById().isEmpty()) {
            throw new IllegalArgumentException("genesisContext must describe state before the genesis block");
        }
    }

    public ChainSnapshot loadCanonicalState() {
        List<BlockRepository.StoredBlock> canonicalChain = repository.loadCanonicalChain();
        return replay(canonicalChain);
    }

    public List<BlockRepository.StoredBlock> loadValidatedCanonicalChain() {
        return loadValidatedCanonicalChainSnapshot().blocks();
    }

    public ValidatedCanonicalChain loadValidatedCanonicalChainSnapshot() {
        List<BlockRepository.StoredBlock> canonicalChain =
                List.copyOf(repository.loadCanonicalChain());
        return new ValidatedCanonicalChain(canonicalChain, replay(canonicalChain));
    }

    public void validateTransactionsForNextBlock(List<? extends LedgerTransaction> transactions) {
        validateTransactionsForNextBlock(transactions, loadCanonicalState());
    }

    public void validateTransactionsForNextBlock(
            List<? extends LedgerTransaction> transactions,
            ChainSnapshot state
    ) {
        if (state == null) {
            throw new IllegalArgumentException("state must not be null");
        }
        long nextHeight = state.tip() == null ? 0 : Math.addExact(state.tip().header().height(), 1);
        validator.validateTransactions(nextHeight, transactions, state.nextBlockContext());
    }

    public BlockProcessingResult processBlock(
            Block block,
            List<? extends LedgerTransaction> transactions
    ) {
        if (block == null) {
            throw new IllegalArgumentException("block must not be null");
        }
        BlockValidationContext parentContext = block.header().height() == 0
                ? genesisContext
                : replay(repository.loadBranch(block.header().previousHash())).nextBlockContext();
        BlockValidationResult validation = validator.validate(block, transactions, parentContext);
        BlockRepository.StoreResult storeResult = repository.storeValidatedBlock(validation);
        ChainSnapshot canonicalState = loadCanonicalState();
        return new BlockProcessingResult(validation, storeResult, canonicalState);
    }

    private ChainSnapshot replay(List<BlockRepository.StoredBlock> chain) {
        if (chain == null) {
            throw new IllegalStateException("Block repository returned a null chain");
        }
        if (chain.isEmpty()) {
            return new ChainSnapshot(null, genesisContext, 0);
        }
        BlockValidationContext context = genesisContext;
        BlockValidationResult result = null;
        for (int index = 0; index < chain.size(); index++) {
            BlockRepository.StoredBlock storedBlock = chain.get(index);
            if (storedBlock == null || storedBlock.block().header().height() != index) {
                throw new IllegalStateException("Stored chain has a missing or out-of-order block");
            }
            result = validator.validate(storedBlock.block(), storedBlock.transactions(), context);
            context = result.childContext();
        }
        if (result == null) {
            throw new IllegalStateException("Non-empty chain replay produced no block result");
        }
        return new ChainSnapshot(result.block(), context, chain.size());
    }

    public record ValidatedCanonicalChain(
            List<BlockRepository.StoredBlock> blocks,
            ChainSnapshot snapshot
    ) {
        public ValidatedCanonicalChain {
            blocks = List.copyOf(blocks);
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }
}
