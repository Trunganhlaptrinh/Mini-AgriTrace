package blockchain;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionCodecTest {
    @Test
    void retainsExplicitJsonNullValuesInTheSignedPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("optionalField", null);
        BatchEvent event = new BatchEvent(
                "pending",
                "event-null-field-test",
                "MANGO-NULL-TEST",
                EventType.CORRECTION,
                Instant.parse("2026-10-04T10:00:00Z"),
                data,
                List.of(new SignatureEnvelope(
                        "farm-1",
                        "farm-key-1",
                        "CORRECTION",
                        java.util.Base64.getEncoder().encodeToString(new byte[64]))));

        assertTrue(TransactionCodec.payloadJson("agritrace-test", event)
                .contains("\"optionalField\":null"));
    }
}
