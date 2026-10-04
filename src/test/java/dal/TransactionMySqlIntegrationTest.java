package dal;

import blockchain.TransactionCodec;
import blockchain.GovernanceCodec;
import blockchain.ValidatedTransaction;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import model.BatchEvent;
import model.EventType;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.SignatureEnvelope;
import model.TransactionStatus;
import org.junit.jupiter.api.Test;
import security.PasswordHasher;
import service.AdminUserService;
import service.AuthenticatedAccount;
import service.AuthenticationService;
import util.DBConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TransactionMySqlIntegrationTest {
    private static final String NETWORK_ID = "agritrace-test";

    @Test
    void persistsIsIdempotentRejectsAndCleansUpAgainstMySql() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("AGRITRACE_DB_INTEGRATION")),
                "Set AGRITRACE_DB_INTEGRATION=true to run MySQL integration tests");

        String eventId = UUID.randomUUID().toString();
        String username = "password-it-" + UUID.randomUUID();
        BatchEvent event = event(eventId, "Mango");
        ValidatedTransaction validated = validated(event);
        GovernanceTransaction governance = governance(UUID.randomUUID().toString());
        ValidatedTransaction validatedGovernance = validated(governance);
        char[] currentPassword = "existing-password".toCharArray();
        char[] newPassword = "replacement-password".toCharArray();
        PasswordHasher passwordHasher = new PasswordHasher();
        UserDAO userDAO = new UserDAO(DBConnection::getConnection);
        long userId = -1;
        TransactionDAO transactionDAO = new TransactionDAO(DBConnection::getConnection, Clock.fixed(
                Instant.parse("2026-10-04T10:00:00Z"), ZoneOffset.UTC));
        TransactionStatusDAO statusDAO = new TransactionStatusDAO(DBConnection::getConnection);

        try {
            userId = new AdminUserService(userDAO, passwordHasher).createUser(
                    new AuthenticatedAccount(1, "integration-admin", "ADMIN", null),
                    username,
                    currentPassword,
                    "ADMIN",
                    null);
            assertEquals(TransactionDAO.SubmissionResult.INSERTED,
                    transactionDAO.insertPending(NETWORK_ID, event, validated, "integration-test"));
            assertEquals(TransactionDAO.SubmissionResult.ALREADY_PRESENT,
                    transactionDAO.insertPending(NETWORK_ID, event, validated, "integration-test"));
            assertEquals(TransactionDAO.SubmissionResult.INSERTED,
                    transactionDAO.insertPending(
                            NETWORK_ID, governance, validatedGovernance, "integration-test"));

            BatchEvent conflictingEvent = event(eventId, "Papaya");
            DuplicateTransactionException duplicate = assertThrows(
                    DuplicateTransactionException.class,
                    () -> transactionDAO.insertPending(
                            NETWORK_ID, conflictingEvent, validated(conflictingEvent), "integration-test"));
            assertEquals("DUPLICATE_TRANSACTION_OR_EVENT", duplicate.getCode());

            assertEquals(TransactionStatus.PENDING,
                    statusDAO.findByTransactionId(validated.transactionId()).orElseThrow().status());
            assertEquals(1, poolEntryCount(validated.transactionId()));
            assertEquals(governance, transactionDAO.findPending(NETWORK_ID).stream()
                    .filter(GovernanceTransaction.class::isInstance)
                    .map(GovernanceTransaction.class::cast)
                    .filter(pending -> pending.transactionId().equals(governance.transactionId()))
                    .findFirst()
                    .orElseThrow());

            assertEquals(TransactionStatusDAO.RejectionResult.UPDATED,
                    statusDAO.markRejected(validated.transactionId(), "INTEGRATION_TEST_REJECTION"));
            var rejected = statusDAO.findByTransactionId(validated.transactionId()).orElseThrow();
            assertEquals(TransactionStatus.REJECTED, rejected.status());
            assertEquals("INTEGRATION_TEST_REJECTION", rejected.rejectionCode());
            assertEquals(0, poolEntryCount(validated.transactionId()));
            assertEquals(1, poolEntryCount(validatedGovernance.transactionId()));

            new AuthenticationService(userDAO, passwordHasher)
                    .changePassword(userId, username, currentPassword, newPassword);
            String changedHash = userDAO.findByUsername(username).orElseThrow().passwordHash();
            assertTrue(passwordHasher.verify(newPassword, changedHash));
            assertFalse(passwordHasher.verify(currentPassword, changedHash));
            assertTrue(userDAO.setActive(userId, false));
            assertFalse(userDAO.findByUsername(username).orElseThrow().active());
            assertTrue(userDAO.setActive(userId, true));
            assertTrue(userDAO.findByUsername(username).orElseThrow().active());
        } finally {
            java.util.Arrays.fill(currentPassword, '\0');
            java.util.Arrays.fill(newPassword, '\0');
            Exception cleanupFailure = null;
            try {
                deleteTransaction(validated.transactionId());
            } catch (Exception exception) {
                cleanupFailure = exception;
            }
            try {
                deleteTransaction(validatedGovernance.transactionId());
            } catch (Exception exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
            try {
                deleteTestUser(username);
            } catch (Exception exception) {
                if (cleanupFailure == null) {
                    cleanupFailure = exception;
                } else {
                    cleanupFailure.addSuppressed(exception);
                }
            }
            if (cleanupFailure != null) {
                throw cleanupFailure;
            }
        }
    }

    private static BatchEvent event(String eventId, String productType) {
        BatchEvent unsigned = new BatchEvent(
                "pending",
                eventId,
                "MYSQL-IT-" + eventId,
                EventType.HARVESTED,
                Instant.parse("2026-10-04T10:00:00Z"),
                Map.of("productType", productType, "quantity", "1.000"),
                List.of(new SignatureEnvelope(
                        "farm-mysql-test",
                        "farm-mysql-test-key",
                        "FARMER_HARVEST",
                        Base64.getEncoder().encodeToString(new byte[64]))));
        return new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.batchCode(),
                unsigned.eventType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.signatures());
    }

    private static ValidatedTransaction validated(BatchEvent event) {
        return new ValidatedTransaction(
                TransactionCodec.transactionId(NETWORK_ID, event),
                TransactionCodec.payloadHash(NETWORK_ID, event));
    }

    private static GovernanceTransaction governance(String eventId) {
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                eventId,
                GovernanceType.REGISTER_PEER,
                Instant.parse("2026-10-04T10:00:00Z"),
                Map.of(
                        "peerId", "mysql-it-peer-" + eventId,
                        "organizationId", "mysql-it-organization",
                        "endpoint", "https://127.0.0.1:8443",
                        "tlsCertificateFingerprint", "a".repeat(64)),
                Base64.getEncoder().encodeToString(new byte[64]));
        return new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.adminSignature());
    }

    private static ValidatedTransaction validated(GovernanceTransaction transaction) {
        return new ValidatedTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, transaction),
                GovernanceCodec.payloadHash(NETWORK_ID, transaction));
    }

    private static int poolEntryCount(String transactionId) throws Exception {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM transaction_pool WHERE tx_id = ?")) {
            statement.setString(1, transactionId);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    private static void deleteTestUser(String username) throws Exception {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM users WHERE username = ?")) {
            statement.setString(1, username);
            statement.executeUpdate();
        }
    }

    private static void deleteTransaction(String transactionId) throws Exception {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "DELETE FROM blockchain_transactions WHERE tx_id = ?")) {
            statement.setString(1, transactionId);
            statement.executeUpdate();
        }
    }

}
