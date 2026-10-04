package blockchain;

import dal.TransactionDAO;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import model.BatchEvent;
import model.GovernanceTransaction;
import model.LedgerTransaction;

public final class TransactionPool implements PendingTransactionSource {
    private static final Comparator<LedgerTransaction> TRANSACTION_ORDER =
            Comparator.comparing(LedgerTransaction::transactionId);

    private final TransactionDAO transactionDAO;
    private final Blockchain blockchain;
    private final String networkId;
    private final String sourceNodeId;

    public TransactionPool(
            String networkId,
            String sourceNodeId,
            TransactionDAO transactionDAO,
            Blockchain blockchain
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        if (sourceNodeId != null && sourceNodeId.isBlank()) {
            throw new IllegalArgumentException("sourceNodeId must be null or non-blank");
        }
        this.networkId = networkId;
        this.sourceNodeId = sourceNodeId;
        this.transactionDAO = Objects.requireNonNull(transactionDAO, "transactionDAO");
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
    }

    public TransactionDAO.SubmissionResult submit(BatchEvent event) {
        requireTransaction(event);
        return submitValidated(event);
    }

    public TransactionDAO.SubmissionResult submit(GovernanceTransaction transaction) {
        requireTransaction(transaction);
        return submitValidated(transaction);
    }

    public List<LedgerTransaction> pendingTransactions() {
        return transactionDAO.findPending(networkId);
    }

    @Override
    public PendingTransactionSource.Selection selectForNextBlock() {
        List<LedgerTransaction> pending = new ArrayList<>(pendingTransactions());
        pending.sort(TRANSACTION_ORDER);
        return selectForState(pending, blockchain.loadCanonicalState());
    }

    private PendingTransactionSource.Selection selectForState(
            List<LedgerTransaction> pending,
            ChainSnapshot canonicalState
    ) {
        List<LedgerTransaction> selected = new ArrayList<>();
        Map<String, String> deferred = new LinkedHashMap<>();
        for (LedgerTransaction candidate : pending) {
            List<LedgerTransaction> trial = new ArrayList<>(selected);
            trial.add(candidate);
            trial.sort(TRANSACTION_ORDER);
            try {
                blockchain.validateTransactionsForNextBlock(trial, canonicalState);
                selected.add(candidate);
                selected.sort(TRANSACTION_ORDER);
            } catch (BlockValidationException exception) {
                deferred.put(candidate.transactionId(), exception.getCode());
            }
        }
        return new PendingTransactionSource.Selection(selected, deferred);
    }

    private TransactionDAO.SubmissionResult submitValidated(
            LedgerTransaction transaction
    ) {
        List<LedgerTransaction> pending = new ArrayList<>(pendingTransactions());
        pending.sort(TRANSACTION_ORDER);
        ChainSnapshot canonicalState = blockchain.loadCanonicalState();
        List<LedgerTransaction> candidateSet = new ArrayList<>(
                selectForState(pending, canonicalState).selected());
        candidateSet.removeIf(pendingTransaction ->
                pendingTransaction.transactionId().equals(transaction.transactionId()));
        candidateSet.add(transaction);
        candidateSet.sort(TRANSACTION_ORDER);
        blockchain.validateTransactionsForNextBlock(candidateSet, canonicalState);
        return transactionDAO.insertPending(networkId, transaction, sourceNodeId);
    }

    private void requireTransaction(LedgerTransaction transaction) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
    }
}
