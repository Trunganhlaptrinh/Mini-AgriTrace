package dal;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.TransactionStatus;
import model.TransactionStatusSnapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionStatusDAOTest {
    private static final String TRANSACTION_ID = "a".repeat(64);

    @Test
    void marksPendingTransactionRejectedAndRemovesItFromPoolAtomically() {
        FakeJdbc jdbc = new FakeJdbc();
        TransactionStatusDAO dao = new TransactionStatusDAO(jdbc::connection);

        TransactionStatusDAO.RejectionResult result = dao.markRejected(TRANSACTION_ID, "INVALID_BLOCK_STATE");

        assertEquals(TransactionStatusDAO.RejectionResult.UPDATED, result);
        assertEquals(2, jdbc.sqlStatements.size());
        assertTrue(jdbc.sqlStatements.get(0).contains("status = 'REJECTED'"));
        assertTrue(jdbc.sqlStatements.get(1).contains("DELETE FROM transaction_pool"));
        assertTrue(jdbc.committed);
        assertFalse(jdbc.rolledBack);
    }

    @Test
    void repeatedRejectionWithTheSameCodeIsIdempotent() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.updateResult = 0;
        jdbc.lockedStatus = Map.of("status", "REJECTED", "rejection_code", "INVALID_BLOCK_STATE");

        TransactionStatusDAO.RejectionResult result =
                new TransactionStatusDAO(jdbc::connection).markRejected(
                        TRANSACTION_ID, "INVALID_BLOCK_STATE");

        assertEquals(TransactionStatusDAO.RejectionResult.ALREADY_REJECTED, result);
        assertTrue(jdbc.rolledBack);
        assertFalse(jdbc.committed);
    }

    @Test
    void rollsBackRejectionIfRemovingThePoolEntryFails() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.failOnUpdateNumber = 2;
        jdbc.failure = new SQLException("pool deletion failed", "HY000");

        assertThrows(PersistenceException.class,
                () -> new TransactionStatusDAO(jdbc::connection)
                        .markRejected(TRANSACTION_ID, "INVALID_BLOCK_STATE"));

        assertFalse(jdbc.committed);
        assertTrue(jdbc.rolledBack);
    }

    @Test
    void returnsCanonicalBlockDetailsForConfirmedTransactions() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.statusRow = Map.of(
                "tx_id", TRANSACTION_ID,
                "event_id", "event-status-dao-test",
                "tx_type", "HARVESTED",
                "payload_hash", "b".repeat(64),
                "status", "CONFIRMED",
                "updated_at", Timestamp.from(Instant.parse("2026-10-04T10:01:00Z")),
                "height", 42L,
                "block_hash", "c".repeat(64),
                "block_timestamp", Timestamp.from(Instant.parse("2026-10-04T10:00:00Z")));

        TransactionStatusSnapshot snapshot = new TransactionStatusDAO(jdbc::connection)
                .findByTransactionId(TRANSACTION_ID)
                .orElseThrow();

        assertEquals(TransactionStatus.CONFIRMED, snapshot.status());
        assertEquals(42L, snapshot.blockHeight());
        assertEquals("c".repeat(64), snapshot.blockHash());
        assertEquals(Instant.parse("2026-10-04T10:00:00Z"), snapshot.blockTimestamp());
    }

    @Test
    void returnsEmptyWhenTheTransactionHasNoLocalStatus() {
        FakeJdbc jdbc = new FakeJdbc();

        assertTrue(new TransactionStatusDAO(jdbc::connection)
                .findByTransactionId(TRANSACTION_ID).isEmpty());
    }

    @Test
    void confirmsOnlyPendingTransactionsWithinTheCallersDatabaseTransaction() throws Exception {
        FakeJdbc jdbc = new FakeJdbc();

        new TransactionStatusDAO(jdbc::connection).confirmPending(jdbc.connection(), TRANSACTION_ID);

        assertEquals(2, jdbc.sqlStatements.size());
        assertTrue(jdbc.sqlStatements.get(0).contains("status = 'CONFIRMED'"));
        assertTrue(jdbc.sqlStatements.get(1).contains("DELETE FROM transaction_pool"));
        assertFalse(jdbc.committed);
    }

    @Test
    void rejectsTransactionsThatAreMissingALocalStatus() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.updateResult = 0;

        TransactionStatusException exception = assertThrows(
                TransactionStatusException.class,
                () -> new TransactionStatusDAO(jdbc::connection)
                        .markRejected(TRANSACTION_ID, "INVALID_BLOCK_STATE"));

        assertEquals("TRANSACTION_NOT_FOUND", exception.getCode());
        assertTrue(jdbc.rolledBack);
    }

    @Test
    void rejectsInvalidIdentifiersBeforeOpeningTheDatabase() {
        FakeJdbc jdbc = new FakeJdbc();

        assertThrows(
                IllegalArgumentException.class,
                () -> new TransactionStatusDAO(jdbc::connection).findByTransactionId("bad-id"));

        assertFalse(jdbc.connectionRequested);
    }

    private static final class FakeJdbc {
        private final List<String> sqlStatements = new ArrayList<>();
        private int updateResult = 1;
        private int failOnUpdateNumber = -1;
        private int updateCount;
        private SQLException failure;
        private Map<String, Object> statusRow = Map.of();
        private Map<String, String> lockedStatus = Map.of();
        private boolean committed;
        private boolean rolledBack;
        private boolean connectionRequested;

        private Connection connection() {
            connectionRequested = true;
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "prepareStatement" -> statement((String) args[0]);
                case "commit" -> {
                    committed = true;
                    yield null;
                }
                case "rollback" -> {
                    rolledBack = true;
                    yield null;
                }
                case "toString" -> "FakeConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(Connection.class, handler);
        }

        private PreparedStatement statement(String sql) {
            sqlStatements.add(sql);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setTimestamp" -> null;
                case "executeUpdate" -> {
                    updateCount++;
                    if (updateCount == failOnUpdateNumber) {
                        throw failure;
                    }
                    yield updateResult;
                }
                case "executeQuery" -> resultSet(sql);
                case "close" -> null;
                case "toString" -> "FakePreparedStatement";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(PreparedStatement.class, handler);
        }

        private ResultSet resultSet(String sql) {
            Map<String, ?> row = sql.contains("SELECT status, rejection_code")
                    ? lockedStatus : statusRow;
            boolean[] consumed = {false};
            boolean[] lastWasNull = {false};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    if (consumed[0] || row.isEmpty()) {
                        yield false;
                    }
                    consumed[0] = true;
                    yield true;
                }
                case "getString" -> row.get((String) args[0]);
                case "getLong" -> {
                    Object value = row.get((String) args[0]);
                    lastWasNull[0] = value == null;
                    yield value == null ? 0L : ((Number) value).longValue();
                }
                case "getTimestamp" -> row.get((String) args[0]);
                case "wasNull" -> lastWasNull[0];
                case "close" -> null;
                case "toString" -> "FakeResultSet";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(ResultSet.class, handler);
        }

        @SuppressWarnings("unchecked")
        private <T> T proxy(Class<T> type, InvocationHandler handler) {
            return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
        }
    }
}
