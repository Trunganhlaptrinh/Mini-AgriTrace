package service;

import blockchain.BlockProcessingResult;
import blockchain.BlockProducer;
import blockchain.TransactionPool;
import dal.TransactionDAO;
import dal.TransactionStatusDAO;
import java.util.Objects;
import java.util.Optional;
import model.BatchEvent;
import model.GovernanceTransaction;
import model.LedgerTransaction;
import model.TransactionStatusSnapshot;

public final class TransactionService {
    private final TransactionPool transactionPool;
    private final TransactionStatusDAO transactionStatusDAO;
    private final BlockProducer blockProducer;

    public TransactionService(
            TransactionPool transactionPool,
            TransactionStatusDAO transactionStatusDAO,
            BlockProducer blockProducer
    ) {
        this.transactionPool = Objects.requireNonNull(transactionPool, "transactionPool");
        this.transactionStatusDAO = Objects.requireNonNull(transactionStatusDAO, "transactionStatusDAO");
        this.blockProducer = Objects.requireNonNull(blockProducer, "blockProducer");
    }

    public TransactionDAO.SubmissionResult submit(BatchEvent event) {
        return transactionPool.submit(event);
    }

    public TransactionDAO.SubmissionResult submit(GovernanceTransaction transaction) {
        return transactionPool.submit(transaction);
    }

    public TransactionDAO.SubmissionResult submit(LedgerTransaction transaction) {
        if (transaction instanceof BatchEvent event) {
            return submit(event);
        }
        if (transaction instanceof GovernanceTransaction governance) {
            return submit(governance);
        }
        throw new IllegalArgumentException("Unsupported ledger transaction type");
    }

    public java.util.List<LedgerTransaction> pendingTransactions() {
        return transactionPool.pendingTransactions();
    }

    public Optional<TransactionStatusSnapshot> findStatus(String transactionId) {
        return transactionStatusDAO.findByTransactionId(transactionId);
    }

    public Optional<BlockProcessingResult> produceNextBlock() {
        return blockProducer.produceNextBlock();
    }
}
