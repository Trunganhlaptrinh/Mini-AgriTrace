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

    /**
     * Admit an authenticated local product transaction and immediately ask the existing
     * producer to include the valid pending pool in a block. Peer synchronization continues
     * to use submit() so receipt from a peer never independently starts block production.
     */
    public synchronized TransactionDAO.SubmissionResult submitAndProduce(BatchEvent event) {
        TransactionDAO.SubmissionResult result = submit(event);
        blockProducer.produceNextBlock();
        return result;
    }

    public synchronized TransactionDAO.SubmissionResult submitAndProduce(
            GovernanceTransaction transaction
    ) {
        TransactionDAO.SubmissionResult result = submit(transaction);
        blockProducer.produceNextBlock();
        return result;
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

    public synchronized Optional<BlockProcessingResult> produceNextBlock() {
        return blockProducer.produceNextBlock();
    }
}
