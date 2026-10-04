package blockchain;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.LedgerTransaction;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerTransactionCodecTest {
    private static final String NETWORK_ID = "agritrace-test";
    private static final Instant EVENT_TIME = Instant.parse("2026-10-04T10:00:00.000Z");
    private static final String SIGNATURE = Base64.getEncoder().encodeToString(new byte[64]);

    @Test
    void roundTripsBatchEventsAndVerifiesTheirCanonicalHashes() {
        BatchEvent unsigned = new BatchEvent(
                "pending",
                "10000000-0000-4000-8000-000000000001",
                "BATCH-1001",
                EventType.HARVESTED,
                EVENT_TIME,
                Map.of("productType", "Mango", "quantity", "12.000"),
                List.of(new SignatureEnvelope("farm-1", "farm-key-1", "FARMER_HARVEST", SIGNATURE)));
        BatchEvent event = new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.batchCode(),
                unsigned.eventType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.signatures());

        LedgerTransaction decoded = LedgerTransactionCodec.decode(
                NETWORK_ID,
                event.transactionId(),
                event.eventId(),
                event.transactionType(),
                TransactionCodec.payloadJson(NETWORK_ID, event),
                TransactionCodec.payloadHash(NETWORK_ID, event),
                TransactionCodec.signaturesJson(event));

        assertEquals(event, decoded);
    }

    @Test
    void roundTripsGovernanceTransactionsAndVerifiesTheirCanonicalHashes() {
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                "10000000-0000-4000-8000-000000000002",
                GovernanceType.REVOKE_PEER,
                EVENT_TIME,
                Map.of("peerId", "peer-1"),
                SIGNATURE);
        GovernanceTransaction event = new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.adminSignature());

        LedgerTransaction decoded = LedgerTransactionCodec.decode(
                NETWORK_ID,
                event.transactionId(),
                event.eventId(),
                event.transactionType(),
                GovernanceCodec.payloadJson(NETWORK_ID, event),
                GovernanceCodec.payloadHash(NETWORK_ID, event),
                GovernanceCodec.signaturesJson(event));

        assertEquals(event, decoded);
    }

    @Test
    void rejectsAStoredPayloadWhoseHashWasChanged() {
        BatchEvent unsigned = new BatchEvent(
                "pending",
                "10000000-0000-4000-8000-000000000003",
                "BATCH-1002",
                EventType.HARVESTED,
                EVENT_TIME,
                Map.of("productType", "Mango"),
                List.of(new SignatureEnvelope("farm-1", "farm-key-1", "FARMER_HARVEST", SIGNATURE)));
        BatchEvent event = new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.batchCode(),
                unsigned.eventType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.signatures());

        assertThrows(IllegalArgumentException.class, () -> LedgerTransactionCodec.decode(
                NETWORK_ID,
                event.transactionId(),
                event.eventId(),
                event.transactionType(),
                TransactionCodec.payloadJson(NETWORK_ID, event),
                "0".repeat(64),
                TransactionCodec.signaturesJson(event)));
    }
}
