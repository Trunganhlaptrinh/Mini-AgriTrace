package service;

import blockchain.BlockProcessingResult;
import blockchain.BlockProducer;
import blockchain.BlockRepository;
import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.ProofOfWork;
import blockchain.TransactionCodec;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import model.BatchEvent;
import model.Block;
import model.EventType;
import model.Organization;
import model.OrganizationKey;
import model.OrganizationType;
import model.PeerRegistration;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShipmentProposalServiceTest {
    private static final String NETWORK_ID = "shipment-test";
    private static final Instant START = Instant.parse("2026-10-04T10:00:00.000Z");
    private static final int DIFFICULTY = 1;

    @Test
    void createsSenderSignedProposalAndSubmitsTwoPartyShipmentAfterCarrierEndorsement()
            throws Exception {
        KeyPair farmKeyPair = keyPair();
        KeyPair carrierKeyPair = keyPair();
        KeyPair warehouseKeyPair = keyPair();
        Map<String, Organization> organizations = Map.of(
                "farm-1", new Organization("farm-1", OrganizationType.FARMER, true),
                "carrier-1", new Organization("carrier-1", OrganizationType.CARRIER, true),
                "warehouse-1", new Organization("warehouse-1", OrganizationType.WAREHOUSE, true));
        Map<String, OrganizationKey> keys = Map.of(
                "farm-key", organizationKey("farm-key", "farm-1", farmKeyPair),
                "carrier-key", organizationKey("carrier-key", "carrier-1", carrierKeyPair),
                "warehouse-key", organizationKey("warehouse-key", "warehouse-1", warehouseKeyPair));
        Fixture fixture = new Fixture(organizations, keys);
        BatchEvent harvest = signedEvent(
                fixture.blockchain,
                farmKeyPair,
                "farm-1",
                "farm-key",
                "FARMER_HARVEST",
                EventType.HARVESTED,
                START.plusMillis(2),
                "MANGO-1",
                Map.of(
                        "productType", "Mango",
                        "variety", "Cat Hoa Loc",
                        "harvestDate", "2026-10-04",
                        "quantity", "12.000",
                        "quantityUnit", "kg",
                        "farmName", "Farm One",
                        "province", "Tien Giang"));
        fixture.mine(harvest);
        BatchEvent packaged = signedEvent(
                fixture.blockchain,
                farmKeyPair,
                "farm-1",
                "farm-key",
                "FARMER_PACKAGED",
                EventType.PACKAGED,
                START.plusMillis(4),
                "MANGO-1",
                Map.of());
        fixture.mine(packaged);

        Clock clock = Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC);
        MemoryProposalRepository proposals = new MemoryProposalRepository();
        AtomicReference<BatchEvent> submitted = new AtomicReference<>();
        AtomicReference<ShipmentProposal> relayed = new AtomicReference<>();
        AtomicReference<BatchEvent> relayedEndorsement = new AtomicReference<>();
        ShipmentProposalService service = new ShipmentProposalService(
                NETWORK_ID,
                fixture.blockchain,
                proposals,
                submitted::set,
                relayed::set,
                (proposal, event) -> relayedEndorsement.set(event),
                clock);
        String expiresAt = START.plusSeconds(20).toString();
        Map<String, Object> shipmentData = Map.of(
                "senderOrganizationId", "farm-1",
                "carrierOrganizationId", "carrier-1",
                "recipientOrganizationId", "warehouse-1",
                "fromProvince", "Tien Giang",
                "toProvince", "Ho Chi Minh City",
                "expiresAt", expiresAt);
        String eventId = UUID.randomUUID().toString();
        BatchEvent unsignedProposal = new BatchEvent(
                "pending", eventId, "MANGO-1", EventType.SHIPPED,
                START.plusMillis(6), shipmentData, List.of());
        SignatureEnvelope senderSignature = sign(
                fixture.blockchain, unsignedProposal, farmKeyPair,
                "farm-1", "farm-key", "SHIPMENT_SENDER");
        ShipmentProposal proposal = new ShipmentProposal(
                UUID.randomUUID().toString(),
                new BatchEvent(
                        "pending", eventId, "MANGO-1", EventType.SHIPPED,
                        unsignedProposal.eventTime(), shipmentData, List.of(senderSignature)),
                Instant.parse(expiresAt),
                ShipmentProposal.Status.AWAITING_CARRIER,
                null);
        AuthenticatedAccount farm = new AuthenticatedAccount(1, "farm-user", "FARMER", "farm-1");
        AuthenticatedAccount carrier =
                new AuthenticatedAccount(2, "carrier-user", "CARRIER", "carrier-1");

        ShipmentProposal created = service.create(farm, proposal);
        SignatureEnvelope carrierSignature = sign(
                fixture.blockchain,
                created.event(),
                carrierKeyPair,
                "carrier-1",
                "carrier-key",
                "SHIPMENT_CARRIER");
        ShipmentProposal result = service.endorse(carrier, proposal.proposalId(), carrierSignature);

        assertEquals(ShipmentProposal.Status.SUBMITTED, result.status());
        assertEquals(proposal.proposalId(), relayed.get().proposalId());
        assertEquals(2, submitted.get().signatures().size());
        assertEquals(submitted.get().transactionId(), result.submittedTransactionId());
        assertEquals(submitted.get(), relayedEndorsement.get());
        fixture.blockchain.validateTransactionsForNextBlock(List.of(submitted.get()));
        assertTrue(service.payloadHash(created).matches("[0-9a-f]{64}"));

        MemoryProposalRepository senderProposals = new MemoryProposalRepository();
        senderProposals.insert(created);
        AtomicReference<BatchEvent> senderNodeSubmission = new AtomicReference<>();
        ShipmentProposalService senderNode = new ShipmentProposalService(
                NETWORK_ID,
                fixture.blockchain,
                senderProposals,
                senderNodeSubmission::set,
                ignoredProposal -> { },
                (ignoredProposal, ignoredEvent) -> { },
                clock);
        PeerRegistration carrierPeer = new PeerRegistration(
                "carrier-node", "carrier-1", "https://carrier.invalid/AgriTrace",
                "a".repeat(64), true);
        PeerRegistration senderPeer = new PeerRegistration(
                "farm-node", "farm-1", "https://farm.invalid/AgriTrace",
                "b".repeat(64), true);

        ShipmentProposal received = senderNode.receiveEndorsementFromPeer(
                carrierPeer, senderPeer, proposal.proposalId(), submitted.get());
        ShipmentProposal duplicate = senderNode.receiveEndorsementFromPeer(
                carrierPeer, senderPeer, proposal.proposalId(), submitted.get());

        assertEquals(ShipmentProposal.Status.SUBMITTED, received.status());
        assertEquals(received, duplicate);
        assertEquals(submitted.get(), senderNodeSubmission.get());
    }

    @Test
    void rejectsEndorsementByAnOrganizationOtherThanSelectedCarrier() {
        MemoryProposalRepository proposals = new MemoryProposalRepository();
        ShipmentProposal proposal = proposalFor("farm-1", "carrier-1", Instant.parse("2026-10-04T10:01:00Z"));
        proposals.insert(proposal);
        ShipmentProposalService service = new ShipmentProposalService(
                NETWORK_ID,
                new Fixture(Map.of(), Map.of()).blockchain,
                proposals,
                event -> { },
                ignoredProposal -> { },
                (ignoredProposal, ignoredEvent) -> { },
                Clock.fixed(START, ZoneOffset.UTC));

        assertThrows(AuthenticationException.class, () -> service.endorse(
                new AuthenticatedAccount(3, "other", "CARRIER", "carrier-2"),
                proposal.proposalId(),
                new SignatureEnvelope("carrier-2", "key", "SHIPMENT_CARRIER",
                        Base64.getEncoder().encodeToString(new byte[64]))));
    }

    private ShipmentProposal proposalFor(String sender, String carrier, Instant expiresAt) {
        Map<String, Object> data = Map.of(
                "senderOrganizationId", sender,
                "carrierOrganizationId", carrier,
                "recipientOrganizationId", "warehouse-1",
                "fromProvince", "Tien Giang",
                "toProvince", "Ho Chi Minh City",
                "expiresAt", expiresAt.toString());
        return new ShipmentProposal(
                UUID.randomUUID().toString(),
                new BatchEvent(
                        "pending", UUID.randomUUID().toString(), "MANGO-1", EventType.SHIPPED,
                        START.plusMillis(2), data,
                        List.of(new SignatureEnvelope(
                                sender, "farm-key", "SHIPMENT_SENDER",
                                Base64.getEncoder().encodeToString(new byte[64])))),
                expiresAt,
                ShipmentProposal.Status.AWAITING_CARRIER,
                null);
    }

    private BatchEvent signedEvent(
            Blockchain blockchain,
            KeyPair keyPair,
            String organizationId,
            String keyId,
            String purpose,
            EventType eventType,
            Instant eventTime,
            String batchCode,
            Map<String, Object> data
    ) throws Exception {
        BatchEvent template = new BatchEvent(
                "pending", UUID.randomUUID().toString(), batchCode, eventType,
                eventTime, data,
                List.of(new SignatureEnvelope(
                        organizationId, keyId, purpose,
                        Base64.getEncoder().encodeToString(new byte[64]))));
        SignatureEnvelope signature = sign(
                blockchain, template, keyPair, organizationId, keyId, purpose);
        BatchEvent withSignature = new BatchEvent(
                "pending", template.eventId(), batchCode, eventType,
                eventTime, data, List.of(signature));
        String transactionId = TransactionCodec.transactionId(NETWORK_ID, withSignature);
        return new BatchEvent(
                transactionId, template.eventId(), batchCode, eventType,
                eventTime, data, List.of(signature));
    }

    private SignatureEnvelope sign(
            Blockchain blockchain,
            BatchEvent event,
            KeyPair keyPair,
            String organizationId,
            String keyId,
            String purpose
    ) throws Exception {
        SignatureEnvelope placeholder = new SignatureEnvelope(
                organizationId, keyId, purpose, Base64.getEncoder().encodeToString(new byte[64]));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(TransactionCodec.signingBytes(NETWORK_ID, event, placeholder));
        return new SignatureEnvelope(
                organizationId, keyId, purpose,
                Base64.getEncoder().encodeToString(derToP1363(signer.sign())));
    }

    private OrganizationKey organizationKey(String keyId, String organizationId, KeyPair pair) {
        return new OrganizationKey(
                keyId,
                organizationId,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                0,
                null);
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] derToP1363(byte[] der) {
        int offset = 2;
        offset++;
        int rLength = der[offset++] & 0xff;
        byte[] r = java.util.Arrays.copyOfRange(der, offset, offset + rLength);
        offset += rLength + 1;
        int sLength = der[offset++] & 0xff;
        byte[] s = java.util.Arrays.copyOfRange(der, offset, offset + sLength);
        byte[] raw = new byte[64];
        copyInteger(r, raw, 0);
        copyInteger(s, raw, 32);
        return raw;
    }

    private void copyInteger(byte[] value, byte[] target, int targetOffset) {
        int sourceOffset = value.length > 32 && value[0] == 0 ? 1 : 0;
        int length = value.length - sourceOffset;
        System.arraycopy(value, sourceOffset, target, targetOffset + 32 - length, length);
    }

    private static final class Fixture {
        private final TestBlockRepository repository = new TestBlockRepository();
        private final Blockchain blockchain;
        private final java.util.ArrayDeque<BatchEvent> pending = new java.util.ArrayDeque<>();
        private final BlockProducer producer;

        private Fixture(
                Map<String, Organization> organizations,
                Map<String, OrganizationKey> keys
        ) {
            Block genesis = ProofOfWork.mine(
                    NETWORK_ID, 0, null, START, DIFFICULTY, List.of(), BigInteger.ZERO);
            blockchain = new Blockchain(
                    new BlockValidator(NETWORK_ID, DIFFICULTY, genesis.hash()),
                    repository,
                    BlockValidationContext.genesis(organizations, keys));
            blockchain.processBlock(genesis, List.of());
            producer = new BlockProducer(
                    NETWORK_ID,
                    DIFFICULTY,
                    Clock.fixed(START, ZoneOffset.UTC),
                    blockchain,
                    () -> new blockchain.PendingTransactionSource.Selection(
                            pending.isEmpty() ? List.of() : List.of(pending.removeFirst()), Map.of()));
        }

        private void mine(BatchEvent event) {
            pending.add(event);
            BlockProcessingResult result = producer.produceNextBlock().orElseThrow();
            assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED,
                    result.persistenceResult());
        }
    }

    private static final class TestBlockRepository implements BlockRepository {
        private final Map<String, StoredBlock> blocks = new HashMap<>();
        private String canonicalTip;

        @Override
        public StoreResult storeValidatedBlock(BlockValidationResult validation) {
            Block block = validation.block();
            blocks.put(block.hash(), new StoredBlock(
                    block,
                    block.transactionIds().stream()
                            .map(validation.transactionsById()::get)
                            .toList()));
            canonicalTip = block.hash();
            return StoreResult.CANONICAL_TIP_UPDATED;
        }

        @Override
        public List<StoredBlock> loadCanonicalChain() {
            return loadBranch(canonicalTip);
        }

        @Override
        public List<StoredBlock> loadBranch(String tipHash) {
            List<StoredBlock> branch = new ArrayList<>();
            String hash = tipHash;
            while (hash != null) {
                StoredBlock stored = blocks.get(hash);
                if (stored == null) {
                    throw new IllegalStateException("unknown block");
                }
                branch.add(stored);
                hash = stored.block().header().previousHash();
            }
            Collections.reverse(branch);
            return List.copyOf(branch);
        }
    }

    private static final class MemoryProposalRepository implements ShipmentProposalRepository {
        private final Map<String, ShipmentProposal> proposals = new HashMap<>();

        @Override
        public void insert(ShipmentProposal proposal) {
            proposals.put(proposal.proposalId(), proposal);
        }

        @Override
        public Optional<ShipmentProposal> find(String proposalId) {
            return Optional.ofNullable(proposals.get(proposalId));
        }

        @Override
        public List<ShipmentProposal> findUnexpiredForCarrier(String carrierOrganizationId) {
            return List.of();
        }

        @Override
        public ShipmentProposal endorse(String proposalId, SignatureEnvelope carrierSignature) {
            ShipmentProposal proposal = proposals.get(proposalId);
            List<SignatureEnvelope> signatures = new ArrayList<>(proposal.event().signatures());
            signatures.add(carrierSignature);
            BatchEvent event = new BatchEvent(
                    "pending", proposal.event().eventId(), proposal.event().batchCode(),
                    EventType.SHIPPED, proposal.event().eventTime(), proposal.event().data(), signatures);
            ShipmentProposal endorsed = new ShipmentProposal(
                    proposalId, event, proposal.expiresAt(), ShipmentProposal.Status.READY, null);
            proposals.put(proposalId, endorsed);
            return endorsed;
        }

        @Override
        public void markSubmitted(String proposalId, String transactionId) {
            ShipmentProposal proposal = proposals.get(proposalId);
            proposals.put(proposalId, new ShipmentProposal(
                    proposalId,
                    new BatchEvent(
                            transactionId, proposal.event().eventId(), proposal.event().batchCode(),
                            EventType.SHIPPED, proposal.event().eventTime(),
                            proposal.event().data(), proposal.event().signatures()),
                    proposal.expiresAt(),
                    ShipmentProposal.Status.SUBMITTED,
                    transactionId));
        }

        @Override
        public void markExpired(String proposalId) {
            ShipmentProposal proposal = proposals.get(proposalId);
            proposals.put(proposalId, new ShipmentProposal(
                    proposalId, proposal.event(), proposal.expiresAt(),
                    ShipmentProposal.Status.EXPIRED, null));
        }
    }
}
