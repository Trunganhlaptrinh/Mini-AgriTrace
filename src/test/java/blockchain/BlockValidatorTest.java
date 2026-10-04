package blockchain;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.EventType;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.LedgerTransaction;
import model.Organization;
import model.OrganizationKey;
import model.OrganizationType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockValidatorTest {
    private static final String NETWORK_ID = "agritrace-test";
    private static final int DIFFICULTY = 1;
    private static final Instant GENESIS_TIME = Instant.parse("2026-10-04T10:00:00Z");
    private static final Instant BLOCK_TIME = Instant.parse("2026-10-04T10:01:00Z");
    private BlockValidator validator;
    private Block genesis;
    private KeyPair adminKeyPair;
    private KeyPair keyPair;
    private Organization organization;
    private OrganizationKey organizationKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        adminKeyPair = generator.generateKeyPair();
        keyPair = generator.generateKeyPair();
        organization = new Organization("farm-1", OrganizationType.FARMER, true);
        organizationKey = new OrganizationKey(
                "farm-key-1",
                "farm-1",
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                0,
                null);
        genesis = mine(0, null, GENESIS_TIME, List.of(), BigInteger.ZERO);
        validator = new BlockValidator(
                NETWORK_ID, DIFFICULTY, genesis.hash(), adminKeyPair.getPublic().getEncoded());
    }

    @Test
    void validatesGenesisAndChildBlockAndReturnsDerivedState() {
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(),
                BlockValidationContext.genesis(Map.of("farm-1", organization),
                        Map.of("farm-key-1", organizationKey)));
        Block child = mine(1, genesis.hash(), BLOCK_TIME, List.of(), genesis.cumulativeWork());

        BlockValidationResult childResult = validator.validate(
                child, List.of(), context(genesis, genesisResult));

        assertEquals(child, childResult.block());
        assertEquals(child.cumulativeWork(), childResult.block().cumulativeWork());
        assertEquals(Set.of(), childResult.transactionIds());
    }

    @Test
    void validatesSignedHarvestTransactionAndAppliesItsStateTransition() throws Exception {
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(),
                BlockValidationContext.genesis(Map.of("farm-1", organization),
                        Map.of("farm-key-1", organizationKey)));
        BatchEvent harvest = signedHarvest();
        Block block = mine(1, genesis.hash(), BLOCK_TIME, List.of(harvest), genesis.cumulativeWork());

        BlockValidationResult result = validator.validate(
                block, List.of(harvest), context(genesis, genesisResult));

        BatchSnapshot snapshot = result.batches().get(harvest.batchCode());
        assertEquals(model.BatchState.HARVESTED, snapshot.state());
        assertEquals("farm-1", snapshot.currentHolderOrganizationId());
        assertEquals(Set.of(harvest.transactionId()), result.transactionIds());
        assertEquals(Set.of(harvest.eventId()), result.eventIds());
        assertEquals(Map.of(harvest.transactionId(), harvest), result.transactionsById());
        assertEquals(result.transactionsById(), result.childContext().transactionsById());
    }

    @Test
    void previewsCandidateTransactionsWithoutNeedingToMineABlock() throws Exception {
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(),
                BlockValidationContext.genesis(Map.of("farm-1", organization),
                        Map.of("farm-key-1", organizationKey)));
        BatchEvent harvest = signedHarvest();

        validator.validateTransactions(1, List.of(harvest), genesisResult.childContext());

        assertTrue(genesisResult.batches().isEmpty());
        assertTrue(genesisResult.transactionIds().isEmpty());
    }

    @Test
    void replaysGovernanceBeforeLaterBatchTransactionsInTheSameBlock() throws Exception {
        GovernanceTransaction registration;
        BatchEvent harvest = signedHarvest();
        do {
            registration = signedOrganizationRegistration();
        } while (registration.transactionId().compareTo(harvest.transactionId()) > 0);
        List<LedgerTransaction> transactions = new java.util.ArrayList<>(List.of(registration, harvest));
        transactions.sort(java.util.Comparator.comparing(LedgerTransaction::transactionId));
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(), BlockValidationContext.genesis(GovernanceRegistry.empty()));
        Block block = mine(1, genesis.hash(), BLOCK_TIME, transactions, genesis.cumulativeWork());

        BlockValidationResult result = validator.validate(
                block,
                transactions,
                context(genesis, genesisResult));

        assertEquals("Mekong Mango Farm", result.governanceRegistry().organizations()
                .get("farm-1").name());
        assertTrue(result.governanceRegistry().organizationKeys().get("farm-key-1").isValidAt(1));
        assertEquals(model.BatchState.HARVESTED, result.batches().get(harvest.batchCode()).state());
        assertEquals(Set.of(registration.transactionId(), harvest.transactionId()),
                result.transactionsById().keySet());
    }

    @Test
    void rejectsAReplayedEventIdFromAncestorChain() throws Exception {
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(),
                BlockValidationContext.genesis(Map.of("farm-1", organization),
                        Map.of("farm-key-1", organizationKey)));
        BatchEvent harvest = signedHarvest();
        Block block = mine(1, genesis.hash(), BLOCK_TIME, List.of(harvest), genesis.cumulativeWork());
        BlockValidationContext replayContext = new BlockValidationContext(
                genesis,
                Set.of(),
                Set.of(harvest.eventId()),
                Map.of("farm-1", organization),
                Map.of("farm-key-1", organizationKey),
                genesisResult.batches(),
                genesisResult.eventsByTransaction());

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> validator.validate(block, List.of(harvest), replayContext));

        assertEquals("DUPLICATE_EVENT", exception.getCode());
    }

    @Test
    void rejectsWrongParentAndIncorrectCumulativeWork() {
        Block incorrectParent = mine(1, "0".repeat(64), BLOCK_TIME, List.of(), genesis.cumulativeWork());
        BlockValidationContext genesisContext = BlockValidationContext.genesis(Map.of(), Map.of());

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> validator.validate(incorrectParent, List.of(), genesisContext));

        assertEquals("INVALID_BLOCK_LINK", exception.getCode());
    }

    @Test
    void rejectsIncorrectCumulativeWork() {
        Block validChild = mine(1, genesis.hash(), BLOCK_TIME, List.of(), genesis.cumulativeWork());
        Block incorrectWork = new Block(
                validChild.header(),
                validChild.transactionIds(),
                BigInteger.ZERO,
                validChild.hash());
        BlockValidationContext context = new BlockValidationContext(
                genesis, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of());

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> validator.validate(incorrectWork, List.of(), context));

        assertEquals("INVALID_CUMULATIVE_WORK", exception.getCode());
    }

    @Test
    void rejectsAGenesisHashThatDiffersFromTheNetworkConfiguration() {
        BlockValidator otherNetworkValidator = new BlockValidator(NETWORK_ID, DIFFICULTY, "0".repeat(64));

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> otherNetworkValidator.validate(
                        genesis, List.of(), BlockValidationContext.genesis(Map.of(), Map.of())));

        assertEquals("WRONG_GENESIS", exception.getCode());
    }

    @Test
    void rejectsBlockHashTampering() {
        Block tampered = new Block(
                genesis.header(),
                genesis.transactionIds(),
                genesis.cumulativeWork(),
                "0".repeat(64));

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> validator.validate(
                        tampered, List.of(), BlockValidationContext.genesis(Map.of(), Map.of())));

        assertEquals("INVALID_BLOCK_HASH", exception.getCode());
    }

    @Test
    void rejectsNonIncreasingBlockTimestamps() {
        Block child = mine(1, genesis.hash(), GENESIS_TIME, List.of(), genesis.cumulativeWork());
        BlockValidationContext context = new BlockValidationContext(
                genesis, Set.of(), Set.of(), Map.of(), Map.of(), Map.of(), Map.of());

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> validator.validate(child, List.of(), context));

        assertEquals("INVALID_BLOCK_TIME", exception.getCode());
    }

    @Test
    void rejectsBlockWithWrongConfiguredDifficulty() {
        Block genesis = ProofOfWork.mine(
                NETWORK_ID, 0, null, GENESIS_TIME, 2, List.of(), BigInteger.ZERO);
        BlockValidator wrongDifficultyValidator = new BlockValidator(
                NETWORK_ID, DIFFICULTY, this.genesis.hash());

        BlockValidationException exception = assertThrows(BlockValidationException.class,
                () -> wrongDifficultyValidator.validate(
                        genesis, List.of(), BlockValidationContext.genesis(Map.of(), Map.of())));

        assertEquals("INVALID_DIFFICULTY", exception.getCode());
    }

    private BlockValidationContext context(Block parent, BlockValidationResult parentResult) {
        assertEquals(parent, parentResult.block());
        return parentResult.childContext();
    }

    private Block mine(
            long height,
            String previousHash,
            Instant timestamp,
            List<? extends LedgerTransaction> transactions,
            BigInteger parentWork
    ) {
        return ProofOfWork.mine(
                NETWORK_ID,
                height,
                previousHash,
                timestamp,
                DIFFICULTY,
                transactions.stream().map(LedgerTransaction::transactionId).toList(),
                parentWork);
    }

    private GovernanceTransaction signedOrganizationRegistration() throws Exception {
        Map<String, String> data = Map.of(
                "organizationId", "farm-1",
                "organizationType", "FARMER",
                "name", "Mekong Mango Farm",
                "province", "Tien Giang",
                "keyId", "farm-key-1",
                "algorithm", "ECDSA_P256_SHA256",
                "publicKey", Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()));
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                UUID.randomUUID().toString(),
                GovernanceType.REGISTER_ORGANIZATION,
                BLOCK_TIME,
                data,
                Base64.getEncoder().encodeToString(new byte[64]));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(adminKeyPair.getPrivate());
        signer.update(GovernanceCodec.signingBytes(NETWORK_ID, unsigned));
        GovernanceTransaction signed = new GovernanceTransaction(
                "pending",
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                data,
                Base64.getEncoder().encodeToString(derToP1363(signer.sign())));
        return new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, signed),
                signed.eventId(),
                signed.governanceType(),
                signed.eventTime(),
                data,
                signed.adminSignature());
    }

    private BatchEvent signedHarvest() throws Exception {
        BatchEvent unsigned = new BatchEvent(
                "pending",
                "event-block-validator-test",
                "MANGO-BLOCK-TEST",
                EventType.HARVESTED,
                BLOCK_TIME,
                Map.of(
                        "productType", "Mango",
                        "variety", "Cat Hoa Loc",
                        "harvestDate", "2026-10-01",
                        "quantity", "1.000",
                        "quantityUnit", "kg",
                        "farmName", "Test Farm",
                        "province", "Tien Giang"),
                List.of(signature(Base64.getEncoder().encodeToString(new byte[64]))));
        SignatureEnvelope placeholder = unsigned.signatures().get(0);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(TransactionCodec.signingBytes(NETWORK_ID, unsigned, placeholder));
        BatchEvent signed = new BatchEvent(
                "pending",
                unsigned.eventId(),
                unsigned.batchCode(),
                unsigned.eventType(),
                unsigned.eventTime(),
                unsigned.data(),
                List.of(signature(Base64.getEncoder().encodeToString(derToP1363(signer.sign())))));
        return new BatchEvent(
                TransactionCodec.transactionId(NETWORK_ID, signed),
                signed.eventId(),
                signed.batchCode(),
                signed.eventType(),
                signed.eventTime(),
                signed.data(),
                signed.signatures());
    }

    private SignatureEnvelope signature(String encodedSignature) {
        return new SignatureEnvelope("farm-1", "farm-key-1", "FARMER_HARVEST", encodedSignature);
    }

    private byte[] derToP1363(byte[] der) {
        int offset = 2;
        int rLength = der[offset + 1] & 0xff;
        java.math.BigInteger r = new java.math.BigInteger(
                1, java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength));
        offset += rLength + 2;
        int sLength = der[offset + 1] & 0xff;
        java.math.BigInteger s = new java.math.BigInteger(
                1, java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength));
        byte[] p1363 = new byte[64];
        copyInteger(r, p1363, 0);
        copyInteger(s, p1363, 32);
        return p1363;
    }

    private void copyInteger(java.math.BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int sourceOffset = bytes.length > 32 ? bytes.length - 32 : 0;
        int length = bytes.length - sourceOffset;
        System.arraycopy(bytes, sourceOffset, target, offset + 32 - length, length);
    }
}
