package network;

import blockchain.BlockRepository;
import blockchain.ProofOfWork;
import blockchain.TransactionCodec;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import model.BatchEvent;
import model.Block;
import model.EventType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LedgerWireCodecTest {
    private static final String NETWORK_ID = "ledger-wire-test";

    @Test
    void roundTripsSignedTransactionWithoutChangingCanonicalIdentifiers() {
        BatchEvent event = signedEvent();

        BatchEvent decoded = (BatchEvent) LedgerTransactionWireCodec.decode(
                NETWORK_ID,
                LedgerTransactionWireCodec.encode(NETWORK_ID, event));

        assertEquals(event, decoded);
        assertEquals(
                event.transactionId(),
                TransactionCodec.transactionId(NETWORK_ID, decoded));
    }

    @Test
    void rejectsUnknownOrTamperedTransactionWireFields() {
        JsonObject wire = LedgerTransactionWireCodec.encode(NETWORK_ID, signedEvent());
        JsonObject withExtra = wire.deepCopy();
        withExtra.addProperty("unexpected", true);
        assertThrows(IllegalArgumentException.class,
                () -> LedgerTransactionWireCodec.decode(NETWORK_ID, withExtra));

        JsonObject tampered = wire.deepCopy();
        tampered.addProperty("payloadHash", "0".repeat(64));
        assertThrows(IllegalArgumentException.class,
                () -> LedgerTransactionWireCodec.decode(NETWORK_ID, tampered));
    }

    @Test
    void roundTripsBlockHeaderAndOrderedTransactionBodies() {
        BatchEvent event = signedEvent();
        Block genesis = ProofOfWork.mine(
                NETWORK_ID, 0, null, Instant.parse("2026-10-05T00:00:00.000Z"),
                1, List.of(), BigInteger.ZERO);
        Block child = ProofOfWork.mine(
                NETWORK_ID, 1, genesis.hash(), Instant.parse("2026-10-05T00:00:00.001Z"),
                1, List.of(event.transactionId()), genesis.cumulativeWork());
        BlockRepository.StoredBlock stored = new BlockRepository.StoredBlock(child, List.of(event));

        BlockRepository.StoredBlock decoded = LedgerBlockWireCodec.decode(
                NETWORK_ID,
                LedgerBlockWireCodec.encode(NETWORK_ID, stored).toString());

        assertEquals(stored, decoded);
    }

    @Test
    void rejectsBlocksWithUnsupportedFieldsOrDifferentNetwork() {
        Block emptyGenesis = ProofOfWork.mine(
                NETWORK_ID, 0, null, Instant.parse("2026-10-05T00:00:00.000Z"),
                1, List.of(), BigInteger.ZERO);
        String encoded = LedgerBlockWireCodec.encode(
                NETWORK_ID,
                new BlockRepository.StoredBlock(emptyGenesis, List.of())).toString();
        JsonObject extra = JsonParser.parseString(encoded).getAsJsonObject();
        extra.addProperty("unexpected", true);

        assertThrows(IllegalArgumentException.class,
                () -> LedgerBlockWireCodec.decode(NETWORK_ID, extra.toString()));
        assertThrows(IllegalArgumentException.class,
                () -> LedgerBlockWireCodec.decode("another-network", encoded));
    }

    private BatchEvent signedEvent() {
        String eventId = UUID.randomUUID().toString();
        SignatureEnvelope signature = new SignatureEnvelope(
                "farm-1",
                "farm-key-1",
                "FARMER_HARVEST",
                Base64.getEncoder().encodeToString(
                        java.util.Arrays.copyOf(
                                "test-signature".getBytes(StandardCharsets.UTF_8), 64)));
        BatchEvent template = new BatchEvent(
                "pending",
                eventId,
                "MANGO-1",
                EventType.HARVESTED,
                Instant.parse("2026-10-05T00:00:00.001Z"),
                Map.of("quantity", "12.500", "quantityUnit", "kg"),
                List.of(signature));
        return new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, template),
                eventId,
                template.batchCode(),
                template.eventType(),
                template.eventTime(),
                template.data(),
                template.signatures());
    }
}
