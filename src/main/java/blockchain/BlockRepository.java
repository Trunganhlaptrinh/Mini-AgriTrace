package blockchain;

import java.util.List;
import model.Block;
import model.LedgerTransaction;

public interface BlockRepository {
    StoreResult storeValidatedBlock(BlockValidationResult validation);

    List<StoredBlock> loadCanonicalChain();

    List<StoredBlock> loadBranch(String tipHash);

    enum StoreResult {
        CANONICAL_TIP_UPDATED,
        FORK_STORED,
        ALREADY_PRESENT
    }

    record StoredBlock(Block block, List<LedgerTransaction> transactions) {
        public StoredBlock {
            if (block == null) {
                throw new IllegalArgumentException("block must not be null");
            }
            transactions = List.copyOf(transactions);
            if (!block.transactionIds().equals(
                    transactions.stream().map(LedgerTransaction::transactionId).toList())) {
                throw new IllegalArgumentException("Stored block body does not match its transaction IDs");
            }
        }
    }
}
