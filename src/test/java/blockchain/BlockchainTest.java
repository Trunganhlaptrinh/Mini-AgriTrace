package blockchain;

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
import java.util.UUID;
import model.BatchEvent;
import model.Block;
import model.EventType;
import model.Organization;
import model.OrganizationKey;
import model.OrganizationType;
import model.SignatureEnvelope;
import model.LedgerTransaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import service.AuthenticatedAccount;
import service.AuthenticationException;
import service.TraceabilityService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockchainTest {
    private static final String NETWORK_ID = "agritrace-test";
    private static final int DIFFICULTY = 1;
    private static final Instant GENESIS_TIME = Instant.parse("2026-10-04T10:00:00.000Z");

    private InMemoryBlockRepository repository;
    private Blockchain blockchain;
    private Block genesis;

    @BeforeEach
    void setUp() {
        genesis = mine(null, GENESIS_TIME);
        repository = new InMemoryBlockRepository();
        blockchain = new Blockchain(
                new BlockValidator(NETWORK_ID, DIFFICULTY, genesis.hash()),
                repository,
                BlockValidationContext.genesis(GovernanceRegistry.empty()));
    }

    @Test
    void replaysForkParentBeforeAcceptingItsChildAndUpdatesCanonicalSnapshot() {
        BlockProcessingResult genesisResult = blockchain.processBlock(genesis, List.of());
        Block firstFork = mine(genesis, GENESIS_TIME.plusMillis(1_000));
        Block secondFork = mine(genesis, GENESIS_TIME.plusMillis(2_000));
        blockchain.processBlock(firstFork, List.of());
        blockchain.processBlock(secondFork, List.of());

        Block losingFork = firstFork.hash().compareTo(secondFork.hash()) > 0
                ? firstFork : secondFork;
        Block childOfFork = mine(losingFork, GENESIS_TIME.plusMillis(3_000));
        BlockProcessingResult result = blockchain.processBlock(childOfFork, List.of());

        assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED, result.persistenceResult());
        assertEquals(childOfFork.hash(), result.canonicalState().tip().hash());
        assertEquals(3, result.canonicalState().blocksValidated());
        assertEquals(childOfFork.hash(), blockchain.loadCanonicalState().tip().hash());
        assertEquals(genesis.hash(), genesisResult.canonicalState().tip().hash());
    }

    @Test
    void doesNotPersistABlockThatFailsValidation() {
        blockchain.processBlock(genesis, List.of());
        int storedCount = repository.storedBlocks.size();
        Block invalidTimestamp = mine(genesis, GENESIS_TIME);

        assertThrows(
                BlockValidationException.class,
                () -> blockchain.processBlock(invalidTimestamp, List.of()));

        assertEquals(storedCount, repository.storedBlocks.size());
    }

    @Test
    void convergesOnCompetingBranchWithHigherCumulativeWorkAndRejectsInvalidBlocks() {
        blockchain.processBlock(genesis, List.of());

        // Branch Alpha: Node 1 produces Block A1 off genesis
        Block blockA1 = mine(genesis, GENESIS_TIME.plusMillis(1_000));
        BlockProcessingResult resA1 = blockchain.processBlock(blockA1, List.of());
        assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED, resA1.persistenceResult());
        assertEquals(blockA1.hash(), resA1.canonicalState().tip().hash());

        // Branch Beta: Node 2 produces Block B1 and Block B2 off genesis (strictly higher cumulative work)
        Block blockB1 = mine(genesis, GENESIS_TIME.plusMillis(2_000));
        Block blockB2 = mine(blockB1, GENESIS_TIME.plusMillis(3_000));

        // Process blockB1 on its candidate branch
        blockchain.processBlock(blockB1, List.of());

        // Process blockB2: higher cumulative work than blockA1
        assertTrue(blockB2.cumulativeWork().compareTo(blockA1.cumulativeWork()) > 0);
        BlockProcessingResult resB2 = blockchain.processBlock(blockB2, List.of());

        assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED, resB2.persistenceResult());
        assertEquals(blockB2.hash(), resB2.canonicalState().tip().hash(),
                "Node must reorg and converge on Branch Beta tip with higher cumulative work");

        // Adversarial test: an invalid block on competing branch must be rejected
        Block invalidCompeting = mine(blockA1, GENESIS_TIME.minusMillis(1_000)); // Non-monotonic timestamp
        assertThrows(BlockValidationException.class,
                () -> blockchain.processBlock(invalidCompeting, List.of()),
                "Adversarial invalid block must be rejected and never become canonical");

        // Canonical tip remains firmly on Branch Beta
        assertEquals(blockB2.hash(), blockchain.loadCanonicalState().tip().hash());
    }

    @Test
    void minesAndProcessesAValidatedPendingBatchEvent() throws Exception {
        KeyPair signer = keyPair();
        Organization farm = new Organization("farm-1", OrganizationType.FARMER, true);
        OrganizationKey farmKey = new OrganizationKey(
                "farm-key-1",
                "farm-1",
                Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()),
                0,
                null);
        BlockValidationContext genesisState = BlockValidationContext.genesis(
                Map.of("farm-1", farm), Map.of("farm-key-1", farmKey));
        Blockchain configuredBlockchain = new Blockchain(
                new BlockValidator(NETWORK_ID, DIFFICULTY, genesis.hash()),
                repository,
                genesisState);
        configuredBlockchain.processBlock(genesis, List.of());

        BatchEvent harvest = signedHarvest(signer);
        PendingTransactionSource pendingTransactions = () -> new PendingTransactionSource.Selection(
                List.of(harvest), Map.of());
        BlockProducer producer = new BlockProducer(
                NETWORK_ID,
                DIFFICULTY,
                Clock.fixed(GENESIS_TIME.plusMillis(10).plusNanos(987_654), ZoneOffset.UTC),
                configuredBlockchain,
                pendingTransactions);

        BlockProcessingResult result = producer.produceNextBlock().orElseThrow();

        assertEquals(1, result.validation().block().header().height());
        assertEquals(GENESIS_TIME.plusMillis(10), result.validation().block().header().timestamp());
        assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED, result.persistenceResult());
        assertEquals(model.BatchState.HARVESTED,
                result.canonicalState().nextBlockContext().batches().get(harvest.batchCode()).state());
        assertEquals(harvest.transactionId(),
                result.canonicalState().nextBlockContext().transactionsById()
                        .get(harvest.transactionId()).transactionId());
    }

    @Test
    void producerDoesNotMineAnEmptyPool() {
        BlockProducer producer = new BlockProducer(
                NETWORK_ID,
                DIFFICULTY,
                Clock.fixed(GENESIS_TIME, ZoneOffset.UTC),
                blockchain,
                () -> new PendingTransactionSource.Selection(List.of(), Map.of()));

        assertEquals(java.util.Optional.empty(), producer.produceNextBlock());
    }

    @Test
    void traceabilityReadsCanonicalBatchAndRestrictsPartnerAccess() throws Exception {
        KeyPair signer = keyPair();
        Organization farm = new Organization("farm-1", OrganizationType.FARMER, true);
        OrganizationKey farmKey = new OrganizationKey(
                "farm-key-1",
                "farm-1",
                Base64.getEncoder().encodeToString(signer.getPublic().getEncoded()),
                0,
                null);
        Blockchain configuredBlockchain = new Blockchain(
                new BlockValidator(NETWORK_ID, DIFFICULTY, genesis.hash()),
                repository,
                BlockValidationContext.genesis(
                        Map.of("farm-1", farm), Map.of("farm-key-1", farmKey)));
        configuredBlockchain.processBlock(genesis, List.of());
        BatchEvent harvest = signedHarvest(signer);
        BlockProducer producer = new BlockProducer(
                NETWORK_ID,
                DIFFICULTY,
                Clock.fixed(GENESIS_TIME, ZoneOffset.UTC),
                configuredBlockchain,
                () -> new PendingTransactionSource.Selection(List.of(harvest), Map.of()));
        producer.produceNextBlock();
        TraceabilityService service = new TraceabilityService(configuredBlockchain);
        AuthenticatedAccount farmAccount =
                new AuthenticatedAccount(1, "farm-user", "FARMER", "farm-1");
        String batchCode = harvest.batchCode();

        TraceabilityService.PublicTrace trace = service.findPublicTrace(batchCode).orElseThrow();
        TraceabilityService.BatchView batch = service.findBatch(batchCode, farmAccount).orElseThrow();

        assertEquals("HARVESTED", trace.currentStatus());
        assertEquals("Mango", trace.publicFields().get("productType"));
        assertEquals(true, trace.verification().valid());
        assertEquals(1, trace.events().size());
        assertEquals(1, trace.events().get(0).blockHeight());
        assertEquals(trace.publicFields(), batch.publicFields());
        assertThrows(
                AuthenticationException.class,
                () -> service.findBatch(batchCode,
                        new AuthenticatedAccount(2, "other-user", "FARMER", "other-farm")));
    }

    private BatchEvent signedHarvest(KeyPair signer) throws Exception {
        String eventId = UUID.randomUUID().toString();
        SignatureEnvelope template = new SignatureEnvelope(
                "farm-1",
                "farm-key-1",
                "FARMER_HARVEST",
                Base64.getEncoder().encodeToString(new byte[64]));
        BatchEvent unsigned = new BatchEvent(
                "pending",
                eventId,
                "BATCH-" + eventId,
                EventType.HARVESTED,
                GENESIS_TIME.plusMillis(2),
                Map.of(
                        "productType", "Mango",
                        "variety", "Cat Hoa Loc",
                        "harvestDate", "2026-10-04",
                        "quantity", "12.000",
                        "quantityUnit", "kg",
                        "farmName", "Test Farm",
                        "province", "Test Province"),
                List.of(template));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(TransactionCodec.signingBytes(NETWORK_ID, unsigned, template));
        SignatureEnvelope signedEnvelope = new SignatureEnvelope(
                template.organizationId(),
                template.keyId(),
                template.purpose(),
                Base64.getEncoder().encodeToString(derToP1363(signature.sign())));
        BatchEvent signed = new BatchEvent(
                "pending",
                unsigned.eventId(),
                unsigned.batchCode(),
                unsigned.eventType(),
                unsigned.eventTime(),
                unsigned.data(),
                List.of(signedEnvelope));
        return new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, signed),
                signed.eventId(),
                signed.batchCode(),
                signed.eventType(),
                signed.eventTime(),
                signed.data(),
                signed.signatures());
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] derToP1363(byte[] der) {
        int offset = 2;
        if (der[offset++] != 0x02) {
            throw new IllegalArgumentException("Invalid ECDSA signature");
        }
        int rLength = der[offset++] & 0xff;
        byte[] r = new byte[rLength];
        System.arraycopy(der, offset, r, 0, rLength);
        offset += rLength;
        if (der[offset++] != 0x02) {
            throw new IllegalArgumentException("Invalid ECDSA signature");
        }
        int sLength = der[offset++] & 0xff;
        byte[] s = new byte[sLength];
        System.arraycopy(der, offset, s, 0, sLength);
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

    private Block mine(Block parent, Instant timestamp) {
        long height = parent == null ? 0 : parent.header().height() + 1;
        String parentHash = parent == null ? null : parent.hash();
        BigInteger parentWork = parent == null ? BigInteger.ZERO : parent.cumulativeWork();
        return ProofOfWork.mine(
                NETWORK_ID,
                height,
                parentHash,
                timestamp,
                DIFFICULTY,
                List.of(),
                parentWork);
    }

    private static final class InMemoryBlockRepository implements BlockRepository {
        private final Map<String, StoredBlock> storedBlocks = new HashMap<>();
        private String canonicalTipHash;

        @Override
        public StoreResult storeValidatedBlock(BlockValidationResult validation) {
            Block block = validation.block();
            if (!storedBlocks.containsKey(block.hash())) {
                List<LedgerTransaction> transactions = block.transactionIds().stream()
                        .map(validation.transactionsById()::get)
                        .toList();
                storedBlocks.put(block.hash(), new StoredBlock(block, transactions));
            }
            if (canonicalTipHash == null) {
                canonicalTipHash = block.hash();
                return StoreResult.CANONICAL_TIP_UPDATED;
            }
            Block current = storedBlocks.get(canonicalTipHash).block();
            if (ChainForkChoice.isPreferred(block, current)) {
                canonicalTipHash = block.hash();
                return StoreResult.CANONICAL_TIP_UPDATED;
            }
            return StoreResult.FORK_STORED;
        }

        @Override
        public List<StoredBlock> loadCanonicalChain() {
            return loadBranch(canonicalTipHash);
        }

        @Override
        public List<StoredBlock> loadBranch(String tipHash) {
            List<StoredBlock> descending = new ArrayList<>();
            String hash = tipHash;
            while (hash != null) {
                StoredBlock stored = storedBlocks.get(hash);
                if (stored == null) {
                    throw new IllegalStateException("Unknown block hash in test repository");
                }
                descending.add(stored);
                hash = stored.block().header().previousHash();
            }
            Collections.reverse(descending);
            return List.copyOf(descending);
        }
    }
}
