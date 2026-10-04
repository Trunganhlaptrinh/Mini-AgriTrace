package controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import service.TraceabilityService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TraceabilityJsonTest {
    @Test
    void exposesApprovedPublicFieldsAtTheDocumentedBatchLevel() {
        TraceabilityService.PublicTrace trace = new TraceabilityService.PublicTrace(
                "MANGO-1",
                Map.of("productType", "Mango", "quantity", "12.000"),
                "HARVESTED",
                "Test Farm",
                List.of(),
                new TraceabilityService.Verification(
                        true, 1, Instant.parse("2026-10-04T10:00:00Z")));

        var batch = TraceabilityJson.publicTrace(trace)
                .getAsJsonObject("data")
                .getAsJsonObject("batch");

        assertEquals("Mango", batch.get("productType").getAsString());
        assertEquals("12.000", batch.get("quantity").getAsString());
        assertEquals("HARVESTED", batch.get("status").getAsString());
        assertEquals("Test Farm", batch.get("currentHolder").getAsString());
        assertFalse(batch.has("publicFields"));
    }
}
