package network;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import model.BatchEvent;
import model.EventType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentEndorsementWireCodecTest {
    @Test
    void roundTripsCompleteSignedShippedEvent() {
        BatchEvent event = new BatchEvent(
                "a".repeat(64),
                UUID.randomUUID().toString(),
                "MANGO-1",
                EventType.SHIPPED,
                Instant.parse("2026-10-04T10:00:00.123Z"),
                Map.of(
                        "senderOrganizationId", "farm-1",
                        "carrierOrganizationId", "carrier-1",
                        "recipientOrganizationId", "warehouse-1",
                        "expiresAt", "2026-10-04T10:01:00Z"),
                List.of(
                        new SignatureEnvelope(
                                "farm-1", "farm-key", "SHIPMENT_SENDER", signature("sender")),
                        new SignatureEnvelope(
                                "carrier-1", "carrier-key", "SHIPMENT_CARRIER", signature("carrier"))));
        String proposalId = UUID.randomUUID().toString();

        ShipmentEndorsementWireCodec.Endorsement decoded =
                ShipmentEndorsementWireCodec.decode(
                        ShipmentEndorsementWireCodec.encode(proposalId, event));

        assertEquals(proposalId, decoded.proposalId());
        assertEquals(event, decoded.event());
        assertTrue(decoded.event().eventTime().toString().endsWith(".123Z"));
    }

    @Test
    void rejectsUnsupportedFieldsAndNonShippedEvents() {
        String valid = ShipmentEndorsementWireCodec.encode(
                UUID.randomUUID().toString(),
                new BatchEvent(
                        "a".repeat(64),
                        UUID.randomUUID().toString(),
                        "MANGO-1",
                        EventType.SHIPPED,
                        Instant.parse("2026-10-04T10:00:00.000Z"),
                        Map.of("senderOrganizationId", "farm-1"),
                        List.of(new SignatureEnvelope(
                                "farm-1", "farm-key", "SHIPMENT_SENDER", signature("sender")))));

        assertThrows(IllegalArgumentException.class,
                () -> ShipmentEndorsementWireCodec.decode(valid.replace(
                        "\"eventType\":\"SHIPPED\"", "\"eventType\":\"HARVESTED\"")));
        assertThrows(IllegalArgumentException.class,
                () -> ShipmentEndorsementWireCodec.decode(valid.replace(
                        "\"proposalId\":", "\"unexpected\":true,\"proposalId\":")));
    }

    private static String signature(String value) {
        return Base64.getEncoder().encodeToString(
                java.util.Arrays.copyOf(value.getBytes(java.nio.charset.StandardCharsets.UTF_8), 64));
    }
}
