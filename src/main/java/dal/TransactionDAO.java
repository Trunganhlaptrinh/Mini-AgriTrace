package dal;

import blockchain.TransactionCodec;
import blockchain.GovernanceCodec;
import blockchain.LedgerTransactionCodec;
import blockchain.ValidatedTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import model.BatchEvent;
import model.GovernanceTransaction;
import model.LedgerTransaction;
import util.DBConnection;

public final class TransactionDAO {
    private static final String INSERT_TRANSACTION = """
            INSERT INTO blockchain_transactions
                (tx_id, event_id, tx_type, payload, payload_hash, signatures, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String INSERT_POOL_ENTRY = """
            INSERT INTO transaction_pool (tx_id, source_node_id)
            VALUES (?, ?)
            """;
    private static final String INSERT_PENDING_STATUS = """
            INSERT INTO node_transaction_status (tx_id, status)
            VALUES (?, 'PENDING')
            """;
    private static final String FIND_DUPLICATE = """
            SELECT tx_id, event_id, payload_hash
            FROM blockchain_transactions
            WHERE tx_id = ? OR event_id = ?
            FOR UPDATE
            """;
    private static final String FIND_PENDING = """
            SELECT t.tx_id, t.event_id, t.tx_type, t.payload, t.payload_hash, t.signatures
            FROM transaction_pool p
            JOIN node_transaction_status s ON s.tx_id = p.tx_id AND s.status = 'PENDING'
            JOIN blockchain_transactions t ON t.tx_id = p.tx_id
            ORDER BY p.received_at, t.tx_id
            """;

    private final ConnectionProvider connectionProvider;
    private final Clock clock;

    public TransactionDAO() {
        this(DBConnection::getConnection, Clock.systemUTC());
    }

    public TransactionDAO(ConnectionProvider connectionProvider, Clock clock) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public SubmissionResult insertPending(
            String networkId,
            BatchEvent event,
            ValidatedTransaction validated,
            String sourceNodeId
    ) {
        validateAcceptedTransaction(networkId, event, validated);
        return insertPending(
                event,
                new EncodedTransaction(
                        event.transactionId(),
                        event.eventId(),
                        event.eventType().name(),
                        TransactionCodec.payloadJson(networkId, event),
                        validated.payloadHash(),
                        TransactionCodec.signaturesJson(event)),
                sourceNodeId);
    }

    public SubmissionResult insertPending(
            String networkId,
            GovernanceTransaction transaction,
            ValidatedTransaction validated,
            String sourceNodeId
    ) {
        validateAcceptedTransaction(networkId, transaction, validated);
        return insertPending(
                transaction,
                new EncodedTransaction(
                        transaction.transactionId(),
                        transaction.eventId(),
                        transaction.transactionType(),
                        GovernanceCodec.payloadJson(networkId, transaction),
                        validated.payloadHash(),
                        GovernanceCodec.signaturesJson(transaction)),
                sourceNodeId);
    }

    public SubmissionResult insertPending(
            String networkId,
            LedgerTransaction transaction,
            String sourceNodeId
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
        if (transaction instanceof BatchEvent event) {
            return insertPending(networkId, event, validated(networkId, event), sourceNodeId);
        }
        if (transaction instanceof GovernanceTransaction governance) {
            return insertPending(
                    networkId, governance, validated(networkId, governance), sourceNodeId);
        }
        throw new IllegalArgumentException("Unsupported ledger transaction type");
    }

    private SubmissionResult insertPending(
            LedgerTransaction transaction,
            EncodedTransaction encoded,
            String sourceNodeId
    ) {
        Timestamp submittedAt = Timestamp.from(clock.instant());
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try {
                    insertTransaction(connection, encoded, submittedAt);
                } catch (SQLException exception) {
                    if (isConstraintViolation(exception)) {
                        rollback(connection, exception);
                        return resolveConstraintViolation(connection, transaction, encoded, exception);
                    }
                    throw exception;
                }
                insertPoolEntry(connection, transaction.transactionId(), sourceNodeId);
                insertPendingStatus(connection, transaction.transactionId());
                connection.commit();
                return SubmissionResult.INSERTED;
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new PersistenceException("Could not persist pending transaction", exception);
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not access the transaction database", exception);
        }
    }

    private void insertTransaction(
            Connection connection,
            EncodedTransaction encoded,
            Timestamp submittedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION)) {
            statement.setString(1, encoded.transactionId());
            statement.setString(2, encoded.eventId());
            statement.setString(3, encoded.transactionType());
            statement.setString(4, encoded.payload());
            statement.setString(5, encoded.payloadHash());
            statement.setString(6, encoded.signatures());
            statement.setTimestamp(7, submittedAt, Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            statement.executeUpdate();
        }
    }

    private void insertPoolEntry(Connection connection, String transactionId, String sourceNodeId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_POOL_ENTRY)) {
            statement.setString(1, transactionId);
            statement.setString(2, sourceNodeId);
            statement.executeUpdate();
        }
    }

    private void insertPendingStatus(Connection connection, String transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_PENDING_STATUS)) {
            statement.setString(1, transactionId);
            statement.executeUpdate();
        }
    }

    private SubmissionResult resolveConstraintViolation(
            Connection connection,
            LedgerTransaction transaction,
            EncodedTransaction encoded,
            SQLException originalException
    ) {
        try (PreparedStatement statement = connection.prepareStatement(FIND_DUPLICATE)) {
            statement.setString(1, transaction.transactionId());
            statement.setString(2, transaction.eventId());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    String existingTransactionId = result.getString("tx_id");
                    String existingEventId = result.getString("event_id");
                    String existingPayloadHash = result.getString("payload_hash");
                    if (transaction.transactionId().equals(existingTransactionId)
                            && transaction.eventId().equals(existingEventId)
                            && encoded.payloadHash().equals(existingPayloadHash)) {
                        return SubmissionResult.ALREADY_PRESENT;
                    }
                }
            }
            throw new DuplicateTransactionException(
                    "DUPLICATE_TRANSACTION_OR_EVENT",
                    "Transaction ID or event ID is already associated with another transaction",
                    originalException);
        } catch (SQLException lookupException) {
            lookupException.addSuppressed(originalException);
            throw new PersistenceException("Could not resolve transaction uniqueness conflict", lookupException);
        }
    }

    private void validateAcceptedTransaction(
            String networkId,
            BatchEvent event,
            ValidatedTransaction validated
    ) {
        if (event == null || validated == null) {
            throw new IllegalArgumentException("event and validated transaction must not be null");
        }
        if (!validated.transactionId().equals(event.transactionId())
                || !validated.transactionId().equals(TransactionCodec.transactionId(networkId, event))
                || !validated.payloadHash().equals(TransactionCodec.payloadHash(networkId, event))) {
            throw new IllegalArgumentException("Validated transaction does not match the event envelope");
        }
    }

    private void validateAcceptedTransaction(
            String networkId,
            GovernanceTransaction transaction,
            ValidatedTransaction validated
    ) {
        if (transaction == null || validated == null) {
            throw new IllegalArgumentException("transaction and validated transaction must not be null");
        }
        if (!validated.transactionId().equals(transaction.transactionId())
                || !validated.transactionId().equals(GovernanceCodec.transactionId(networkId, transaction))
                || !validated.payloadHash().equals(GovernanceCodec.payloadHash(networkId, transaction))) {
            throw new IllegalArgumentException("Validated transaction does not match the governance envelope");
        }
    }

    public List<LedgerTransaction> findPending(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_PENDING);
             ResultSet result = statement.executeQuery()) {
            List<LedgerTransaction> pending = new ArrayList<>();
            while (result.next()) {
                try {
                    pending.add(LedgerTransactionCodec.decode(
                            networkId,
                            result.getString("tx_id"),
                            result.getString("event_id"),
                            result.getString("tx_type"),
                            result.getString("payload"),
                            result.getString("payload_hash"),
                            result.getString("signatures")));
                } catch (IllegalArgumentException | IllegalStateException exception) {
                    throw new PersistenceException("Pending transaction data is malformed", exception);
                }
            }
            return List.copyOf(pending);
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load pending transactions", exception);
        }
    }

    private ValidatedTransaction validated(String networkId, BatchEvent event) {
        return new ValidatedTransaction(
                TransactionCodec.transactionId(networkId, event),
                TransactionCodec.payloadHash(networkId, event));
    }

    private ValidatedTransaction validated(String networkId, GovernanceTransaction transaction) {
        return new ValidatedTransaction(
                GovernanceCodec.transactionId(networkId, transaction),
                GovernanceCodec.payloadHash(networkId, transaction));
    }

    private boolean isConstraintViolation(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getSQLState() != null && current.getSQLState().startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    public enum SubmissionResult {
        INSERTED,
        ALREADY_PRESENT
    }

    private record EncodedTransaction(
            String transactionId,
            String eventId,
            String transactionType,
            String payload,
            String payloadHash,
            String signatures
    ) {
    }
}
