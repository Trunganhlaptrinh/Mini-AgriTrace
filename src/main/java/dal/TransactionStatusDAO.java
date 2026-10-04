package dal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import model.TransactionStatus;
import model.TransactionStatusSnapshot;
import util.DBConnection;

public final class TransactionStatusDAO {
    private static final String FIND_STATUS = """
            SELECT t.tx_id, t.event_id, t.tx_type, t.payload_hash,
                   s.status, s.rejection_code, s.updated_at,
                   b.height, b.block_hash, b.block_timestamp
            FROM blockchain_transactions t
            JOIN node_transaction_status s ON s.tx_id = t.tx_id
            LEFT JOIN (
                SELECT bt.tx_id, chain_block.height, chain_block.block_hash, chain_block.block_timestamp
                FROM block_transactions bt
                JOIN blockchain_blocks chain_block
                    ON chain_block.block_hash = bt.block_hash
                WHERE chain_block.is_canonical = TRUE
            ) b ON b.tx_id = t.tx_id
            WHERE t.tx_id = ?
            """;
    private static final String FIND_STATUS_FOR_UPDATE = """
            SELECT status, rejection_code
            FROM node_transaction_status
            WHERE tx_id = ?
            FOR UPDATE
            """;
    private static final String REJECT_PENDING = """
            UPDATE node_transaction_status
            SET status = 'REJECTED', rejection_code = ?
            WHERE tx_id = ? AND status = 'PENDING'
            """;
    private static final String CONFIRM_PENDING = """
            UPDATE node_transaction_status
            SET status = 'CONFIRMED', rejection_code = NULL
            WHERE tx_id = ? AND status = 'PENDING'
            """;
    private static final String REMOVE_FROM_POOL =
            "DELETE FROM transaction_pool WHERE tx_id = ?";
    private final ConnectionProvider connectionProvider;

    public TransactionStatusDAO() {
        this(DBConnection::getConnection);
    }

    public TransactionStatusDAO(ConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    public Optional<TransactionStatusSnapshot> findByTransactionId(String transactionId) {
        requireTransactionId(transactionId);
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_STATUS)) {
            statement.setString(1, transactionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(readSnapshot(result));
                } catch (IllegalArgumentException exception) {
                    throw new PersistenceException("Stored transaction status is inconsistent", exception);
                }
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not read transaction status", exception);
        }
    }

    public RejectionResult markRejected(String transactionId, String rejectionCode) {
        requireTransactionId(transactionId);
        if (rejectionCode == null || rejectionCode.isBlank() || rejectionCode.length() > 80) {
            throw new IllegalArgumentException("rejectionCode must contain 1 to 80 characters");
        }

        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement statement = connection.prepareStatement(REJECT_PENDING)) {
                statement.setString(1, rejectionCode);
                statement.setString(2, transactionId);
                if (statement.executeUpdate() == 1) {
                    removeFromPool(connection, transactionId);
                    connection.commit();
                    return RejectionResult.UPDATED;
                }
                StoredStatus existing = findStatusForUpdate(connection, transactionId);
                if (existing == null) {
                    throw new TransactionStatusException(
                            "TRANSACTION_NOT_FOUND", "Transaction status does not exist");
                }
                if (existing.status() == TransactionStatus.REJECTED
                        && rejectionCode.equals(existing.rejectionCode())) {
                    connection.rollback();
                    return RejectionResult.ALREADY_REJECTED;
                }
                throw new TransactionStatusException(
                        "TRANSACTION_STATUS_CONFLICT", "Only pending transactions can be rejected");
            } catch (SQLException exception) {
                rollback(connection, exception);
                throw new PersistenceException("Could not update transaction status", exception);
            } catch (RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not access the transaction database", exception);
        }
    }

    void confirmPending(Connection connection, String transactionId) throws SQLException {
        requireTransactionId(transactionId);
        try (PreparedStatement statement = connection.prepareStatement(CONFIRM_PENDING)) {
            statement.setString(1, transactionId);
            if (statement.executeUpdate() != 1) {
                throw new TransactionStatusException(
                        "TRANSACTION_STATUS_CONFLICT",
                        "Only a pending transaction can be confirmed");
            }
        }
        removeFromPool(connection, transactionId);
    }

    private TransactionStatusSnapshot readSnapshot(ResultSet result) throws SQLException {
        TransactionStatus status = TransactionStatus.valueOf(result.getString("status"));
        long height = result.getLong("height");
        Long blockHeight = result.wasNull() ? null : height;
        String blockHash = result.getString("block_hash");
        Timestamp blockTime = result.getTimestamp("block_timestamp", utcCalendar());
        if (status == TransactionStatus.CONFIRMED
                && (blockHeight == null || blockHash == null || blockTime == null)) {
            throw new SQLException("Confirmed transaction has no canonical block metadata");
        }
        if (status != TransactionStatus.CONFIRMED
                && (blockHeight != null || blockHash != null || blockTime != null)) {
            throw new SQLException("Non-confirmed transaction has canonical block metadata");
        }
        return new TransactionStatusSnapshot(
                result.getString("tx_id"),
                result.getString("event_id"),
                result.getString("tx_type"),
                result.getString("payload_hash"),
                status,
                result.getString("rejection_code"),
                result.getTimestamp("updated_at", utcCalendar()).toInstant(),
                blockHeight,
                blockHash,
                blockTime == null ? null : blockTime.toInstant());
    }

    private StoredStatus findStatusForUpdate(Connection connection, String transactionId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_STATUS_FOR_UPDATE)) {
            statement.setString(1, transactionId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new StoredStatus(
                        TransactionStatus.valueOf(result.getString("status")),
                        result.getString("rejection_code"));
            }
        }
    }

    private void removeFromPool(Connection connection, String transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(REMOVE_FROM_POOL)) {
            statement.setString(1, transactionId);
            statement.executeUpdate();
        }
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private void requireTransactionId(String transactionId) {
        if (transactionId == null || !transactionId.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("transactionId must be a lowercase SHA-256 hex digest");
        }
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private record StoredStatus(TransactionStatus status, String rejectionCode) {
    }

    public enum RejectionResult {
        UPDATED,
        ALREADY_REJECTED
    }
}
