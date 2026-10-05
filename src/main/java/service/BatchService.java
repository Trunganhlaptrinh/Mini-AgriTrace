package service;

import dal.TransactionDAO;
import java.util.Objects;
import model.BatchEvent;

public final class BatchService {
    private final BatchTransactionSubmitter transactionSubmitter;

    public BatchService(TransactionService transactionService) {
        this(transactionService::submitAndProduce);
    }

    public BatchService(BatchTransactionSubmitter transactionSubmitter) {
        this.transactionSubmitter = Objects.requireNonNull(
                transactionSubmitter, "transactionSubmitter");
    }

    public TransactionDAO.SubmissionResult submit(
            AuthenticatedAccount actor,
            BatchEvent event
    ) {
        if (actor == null) {
            throw new AuthenticationException(
                    "UNAUTHENTICATED", "Authentication is required", 401);
        }
        if (actor.organizationId() == null || actor.organizationId().isBlank()) {
            throw new AuthenticationException(
                    "FORBIDDEN", "An organization account is required to submit batch events", 403);
        }
        if (event == null) {
            throw new IllegalArgumentException("event must not be null");
        }
        boolean actorSigned = event.signatures().stream()
                .anyMatch(signature -> actor.organizationId().equals(signature.organizationId()));
        if (!actorSigned) {
            throw new AuthenticationException(
                    "FORBIDDEN", "The authenticated organization must sign the submitted event", 403);
        }
        return transactionSubmitter.submit(event);
    }

    @FunctionalInterface
    public interface BatchTransactionSubmitter {
        TransactionDAO.SubmissionResult submit(BatchEvent event);
    }
}
