package service;

import dal.TransactionDAO;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import model.BatchEvent;
import model.EventType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BatchServiceTest {
    private static final SignatureEnvelope FARMER_SIGNATURE =
            new SignatureEnvelope("farm-1", "farm-key-1", "FARMER_HARVEST",
                    java.util.Base64.getEncoder().encodeToString(new byte[64]));

    @Test
    void admitsOnlyWhenAuthenticatedOrganizationSignedTheEvent() {
        AtomicReference<BatchEvent> submitted = new AtomicReference<>();
        BatchService service = new BatchService(event -> {
            submitted.set(event);
            return TransactionDAO.SubmissionResult.INSERTED;
        });

        BatchEvent event = harvest();
        assertEquals(TransactionDAO.SubmissionResult.INSERTED,
                service.submit(new AuthenticatedAccount(8, "farmer.one", "FARMER", "farm-1"), event));
        assertEquals(event, submitted.get());
    }

    @Test
    void refusesSubmittingOnBehalfOfAnOrganizationThatDidNotSign() {
        AtomicReference<BatchEvent> submitted = new AtomicReference<>();
        BatchService service = new BatchService(event -> {
            submitted.set(event);
            return TransactionDAO.SubmissionResult.INSERTED;
        });

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> service.submit(
                        new AuthenticatedAccount(9, "farmer.two", "FARMER", "farm-2"),
                        harvest()));

        assertEquals("FORBIDDEN", exception.getCode());
        assertNull(submitted.get());
    }

    @Test
    void requiresAnOrganizationBoundAccount() {
        BatchService service = new BatchService(event -> TransactionDAO.SubmissionResult.INSERTED);

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> service.submit(
                        new AuthenticatedAccount(1, "admin", "ADMIN", null),
                        harvest()));

        assertEquals("FORBIDDEN", exception.getCode());
    }

    @Test
    void rejectsBatchCodesThatCannotFitTheProjectionSchema() {
        assertThrows(IllegalArgumentException.class, () -> new BatchEvent(
                "pending",
                "event-1",
                "B".repeat(101),
                EventType.HARVESTED,
                Instant.parse("2026-10-04T10:00:00.000Z"),
                Map.of(),
                List.of(FARMER_SIGNATURE)));
    }

    private BatchEvent harvest() {
        return new BatchEvent(
                "pending",
                "event-1",
                "MANGO-1",
                EventType.HARVESTED,
                Instant.parse("2026-10-04T10:00:00.000Z"),
                Map.of(
                        "productType", "Mango",
                        "variety", "Cat Hoa Loc",
                        "harvestDate", "2026-10-04",
                        "quantity", "1.000",
                        "quantityUnit", "kg",
                        "farmName", "Farm One",
                        "province", "Tien Giang"),
                List.of(FARMER_SIGNATURE));
    }
}
