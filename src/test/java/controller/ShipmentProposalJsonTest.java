package controller;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ShipmentProposalJsonTest {
    @Test
    void preservesTheExactSignedExpiryRepresentationInProposalResponses() {
        String expiresAt = "2026-10-04T10:00:20Z";
        ShipmentProposal proposal = new ShipmentProposal(
                "00000000-0000-0000-0000-000000000001",
                new BatchEvent(
                        "pending",
                        "00000000-0000-0000-0000-000000000002",
                        "MANGO-1",
                        EventType.SHIPPED,
                        Instant.parse("2026-10-04T10:00:06.000Z"),
                        Map.of(
                                "senderOrganizationId", "farm-1",
                                "carrierOrganizationId", "carrier-1",
                                "recipientOrganizationId", "warehouse-1",
                                "fromProvince", "Tien Giang",
                                "toProvince", "Ho Chi Minh City",
                                "expiresAt", expiresAt),
                        List.of(new SignatureEnvelope(
                                "farm-1", "farm-key", "SHIPMENT_SENDER",
                                java.util.Base64.getEncoder().encodeToString(new byte[64])))),
                Instant.parse(expiresAt),
                ShipmentProposal.Status.AWAITING_CARRIER,
                null);

        var json = ShipmentProposalJson.proposal(proposal, "a".repeat(64));

        assertEquals(expiresAt, json.get("expiresAt").getAsString());
        assertEquals("AWAITING_CARRIER", json.get("status").getAsString());
        assertEquals("a".repeat(64), json.get("payloadHash").getAsString());
    }
}
