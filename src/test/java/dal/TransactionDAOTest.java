package dal;

import blockchain.TransactionCodec;
import blockchain.ValidatedTransaction;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionDAOTest {
    private static final String NETWORK_ID = "agritrace-test";
    private static final Instant SUBMITTED_AT = Instant.parse("2026-10-04T10:00:00Z");
    private static final BatchEvent EVENT = event();
    private static final ValidatedTransaction VALIDATED = new ValidatedTransaction(
            TransactionCodec.transactionId(NETWORK_ID, EVENT),
            TransactionCodec.payloadHash(NETWORK_ID, EVENT));

    @Test
    void persistsTransactionPoolAndPendingStatusAtomically() {
        FakeJdbc jdbc = new FakeJdbc();

        TransactionDAO.SubmissionResult result = dao(jdbc).insertPending(
                NETWORK_ID, EVENT, VALIDATED, "node-farm-1");

        assertEquals(TransactionDAO.SubmissionResult.INSERTED, result);
        assertTrue(jdbc.committed);
        assertFalse(jdbc.rolledBack);
        assertEquals(3, jdbc.statements.size());
        assertTrue(jdbc.statements.get(0).sql.contains("INSERT INTO blockchain_transactions"));
        assertEquals(VALIDATED.transactionId(), jdbc.statements.get(0).parameters.get(1));
        assertEquals(EVENT.eventId(), jdbc.statements.get(0).parameters.get(2));
        assertEquals(EVENT.eventType().name(), jdbc.statements.get(0).parameters.get(3));
        assertEquals(TransactionCodec.payloadJson(NETWORK_ID, EVENT), jdbc.statements.get(0).parameters.get(4));
        assertEquals(VALIDATED.payloadHash(), jdbc.statements.get(0).parameters.get(5));
        assertEquals(TransactionCodec.signaturesJson(EVENT), jdbc.statements.get(0).parameters.get(6));
        assertEquals(Timestamp.from(SUBMITTED_AT), jdbc.statements.get(0).parameters.get(7));
        assertEquals("node-farm-1", jdbc.statements.get(1).parameters.get(2));
        assertTrue(jdbc.statements.get(2).sql.contains("'PENDING'"));
    }

    @Test
    void rollsBackAllWritesWhenPendingStatusInsertFails() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.failOnUpdateNumber = 3;
        jdbc.failure = new SQLException("status insert failed", "HY000");

        PersistenceException exception = assertThrows(PersistenceException.class,
                () -> dao(jdbc).insertPending(NETWORK_ID, EVENT, VALIDATED, null));

        assertEquals("Could not persist pending transaction", exception.getMessage());
        assertFalse(jdbc.committed);
        assertTrue(jdbc.rolledBack);
    }

    @Test
    void treatsTheSameAcceptedTransactionAsAnIdempotentSubmission() {
        FakeJdbc jdbc = new FakeJdbc();
        jdbc.failOnUpdateNumber = 1;
        jdbc.failure = new SQLException("duplicate transaction ID", "23000", 1062);
        jdbc.existingTransaction = Map.of(
                "tx_id", VALIDATED.transactionId(),
                "event_id", EVENT.eventId(),
                "payload_hash", VALIDATED.payloadHash());

        TransactionDAO.SubmissionResult result = dao(jdbc).insertPending(
                NETWORK_ID, EVENT, VALIDATED, "peer-node");

        assertEquals(TransactionDAO.SubmissionResult.ALREADY_PRESENT, result);
        assertFalse(jdbc.committed);
        assertTrue(jdbc.rolledBack);
        assertTrue(jdbc.queryExecuted);
    }

    @Test
    void rejectsValidationMetadataThatDoesNotMatchBeforeOpeningConnection() {
        FakeJdbc jdbc = new FakeJdbc();
        ValidatedTransaction mismatched = new ValidatedTransaction(
                "0".repeat(64), VALIDATED.payloadHash());

        assertThrows(IllegalArgumentException.class,
                () -> dao(jdbc).insertPending(NETWORK_ID, EVENT, mismatched, null));
        assertFalse(jdbc.connectionRequested);
    }

    private TransactionDAO dao(FakeJdbc jdbc) {
        return new TransactionDAO(jdbc::connection, Clock.fixed(SUBMITTED_AT, ZoneOffset.UTC));
    }

    private static BatchEvent event() {
        BatchEvent event = new BatchEvent(
                "pending",
                "event-transaction-dao-test",
                "MANGO-2026-0001",
                EventType.HARVESTED,
                Instant.parse("2026-10-01T02:00:00Z"),
                Map.of("productType", "Mango", "quantity", "1200.000"),
                List.of(new SignatureEnvelope(
                        "farm-1",
                        "farm-key-1",
                        "FARMER_HARVEST",
                        Base64.getEncoder().encodeToString(new byte[64]))));
        return new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, event),
                event.eventId(),
                event.batchCode(),
                event.eventType(),
                event.eventTime(),
                event.data(),
                event.signatures());
    }

    private static final class FakeJdbc {
        private final List<StatementRecord> statements = new ArrayList<>();
        private int failOnUpdateNumber = -1;
        private int updateCount;
        private SQLException failure;
        private Map<String, String> existingTransaction = Map.of();
        private boolean connectionRequested;
        private boolean committed;
        private boolean rolledBack;
        private boolean queryExecuted;

        private Connection connection() {
            connectionRequested = true;
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setAutoCommit", "close" -> null;
                case "prepareStatement" -> prepareStatement((String) args[0]);
                case "commit" -> {
                    committed = true;
                    yield null;
                }
                case "rollback" -> {
                    rolledBack = true;
                    yield null;
                }
                case "isClosed" -> false;
                case "toString" -> "FakeConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(Connection.class, handler);
        }

        private PreparedStatement prepareStatement(String sql) {
            StatementRecord record = new StatementRecord(sql);
            statements.add(record);
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setTimestamp" -> {
                    record.parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeUpdate" -> {
                    updateCount++;
                    if (updateCount == failOnUpdateNumber) {
                        throw failure;
                    }
                    yield 1;
                }
                case "executeQuery" -> {
                    queryExecuted = true;
                    yield resultSet();
                }
                case "close" -> null;
                case "toString" -> "FakePreparedStatement";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            return proxy(PreparedStatement.class, handler);
        }

        private ResultSet resultSet() {
            boolean[] consumed = {false};
            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "next" -> {
                    if (consumed[0] || existingTransaction.isEmpty()) {
                        yield false;
                    }
                    consumed[0] = true;
                    yield true;
                }
                case "getString" -> existingTransaction.get((String) args[0]);
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

    private static final class StatementRecord {
        private final String sql;
        private final Map<Integer, Object> parameters = new HashMap<>();

        private StatementRecord(String sql) {
            this.sql = sql;
        }
    }
}
