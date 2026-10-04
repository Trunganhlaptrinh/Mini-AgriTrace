package service;

import java.util.List;
import model.BatchSnapshot;
import model.BatchState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceabilityServiceTest {
    @Test
    void permitsDesignatedRecipientToReadBatchWhileShipmentIsInTransit() {
        BatchSnapshot inTransit = new BatchSnapshot(
                "MANGO-1",
                BatchState.IN_TRANSIT,
                "farm-1",
                "farm-1",
                "shipment-tx",
                "carrier-1",
                "warehouse-1",
                "shipment-tx");
        AuthenticatedAccount recipient =
                new AuthenticatedAccount(1, "warehouse-user", "WAREHOUSE", "warehouse-1");
        AuthenticatedAccount unrelated =
                new AuthenticatedAccount(2, "other-user", "WAREHOUSE", "warehouse-2");
        AuthenticatedAccount admin =
                new AuthenticatedAccount(3, "admin", "ADMIN", null);

        assertTrue(TraceabilityService.canAccess(recipient, inTransit, List.of()));
        assertFalse(TraceabilityService.canAccess(unrelated, inTransit, List.of()));
        assertTrue(TraceabilityService.canAccess(admin, inTransit, List.of()));
    }
}
