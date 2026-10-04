package dal;

import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.BlockRepository;
import blockchain.GovernanceCodec;
import blockchain.ProofOfWork;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import model.Block;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.OrganizationType;
import model.TransactionStatus;
import org.junit.jupiter.api.Test;
import util.DBConnection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class BlockMySqlIntegrationTest {
    private static final String NETWORK_ID = "agritrace-test";
    private static final int DIFFICULTY = 1;
    private static final Instant GENESIS_TIME = Instant.parse("2026-10-04T10:00:00.000Z");
    private static final Instant EVENT_TIME = Instant.parse("2026-10-04T10:00:01.000Z");

    @Test
    void persistsForksReorganizesCanonicalStateAndRebuildsLocalOrganizationAvailability()
            throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("AGRITRACE_DB_BLOCK_INTEGRATION")),
                "Set AGRITRACE_DB_BLOCK_INTEGRATION=true for isolated block persistence integration tests");
        assumeTrue(isIsolatedDatabaseEmpty(),
                "Block integration requires an empty dedicated database; it will not modify a populated chain");

        String suffix = UUID.randomUUID().toString();
        String organizationId = "block-it-org-" + suffix;
        String keyId = "block-it-key-" + suffix;
        String username = "block-it-" + suffix;
        KeyPair admin = keyPair();
        KeyPair organization = keyPair();
        GovernanceTransaction registration = registration(organizationId, keyId, organization, admin);

        Block genesis = ProofOfWork.mine(
                NETWORK_ID, 0, null, GENESIS_TIME, DIFFICULTY, List.of(), BigInteger.ZERO);
        BlockValidator validator = new BlockValidator(
                NETWORK_ID, DIFFICULTY, genesis.hash(), admin.getPublic().getEncoded());
        BlockValidationResult genesisResult = validator.validate(
                genesis, List.of(), BlockValidationContext.genesis(Map.of(), Map.of()));
        BlockDAO blockDAO = new BlockDAO(
                DBConnection::getConnection,
                Clock.fixed(EVENT_TIME, ZoneOffset.UTC),
                NETWORK_ID,
                genesis.hash());
        Block firstBranch = null;
        Block preferredFork = null;
        try {
            assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED,
                    blockDAO.storeValidatedBlock(genesisResult));

            firstBranch = ProofOfWork.mine(
                    NETWORK_ID,
                    1,
                    genesis.hash(),
                    EVENT_TIME,
                    DIFFICULTY,
                    List.of(registration.transactionId()),
                    genesis.cumulativeWork());
            BlockValidationResult firstResult = validator.validate(
                    firstBranch,
                    List.of(registration),
                    genesisResult.childContext());
            assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED,
                    blockDAO.storeValidatedBlock(firstResult));

            try (Connection connection = DBConnection.getConnection();
                 PreparedStatement statement = connection.prepareStatement("""
                         INSERT INTO users
                             (username, password_hash, role, organization_id, is_active)
                         VALUES (?, ?, 'FARMER', ?, TRUE)
                         """)) {
                statement.setString(1, username);
                statement.setString(2, "integration-test-password-hash");
                statement.setString(3, organizationId);
                statement.executeUpdate();
            }
            assertEquals(TransactionStatus.CONFIRMED,
                    new TransactionStatusDAO(DBConnection::getConnection)
                            .findByTransactionId(registration.transactionId()).orElseThrow().status());

            preferredFork = mineLowerHashFork(genesis, firstBranch);
            BlockValidationResult forkResult = validator.validate(
                    preferredFork, List.of(), genesisResult.childContext());
            assertEquals(BlockRepository.StoreResult.CANONICAL_TIP_UPDATED,
                    blockDAO.storeValidatedBlock(forkResult));

            List<BlockRepository.StoredBlock> canonicalChain = blockDAO.loadCanonicalChain();
            List<BlockRepository.StoredBlock> firstBranchChain = blockDAO.loadBranch(firstBranch.hash());
            assertEquals(preferredFork.hash(),
                    canonicalChain.get(canonicalChain.size() - 1).block().hash());
            assertEquals(firstBranch.hash(),
                    firstBranchChain.get(firstBranchChain.size() - 1).block().hash());
            assertEquals(TransactionStatus.PENDING,
                    new TransactionStatusDAO(DBConnection::getConnection)
                            .findByTransactionId(registration.transactionId()).orElseThrow().status());
            assertEquals(1, poolEntryCount(registration.transactionId()));
            assertEquals(0, countWhere(
                    "SELECT COUNT(*) FROM organizations WHERE organization_id = ?", organizationId));
            assertEquals(1, countWhere(
                    "SELECT COUNT(*) FROM users WHERE username = ? AND is_active = TRUE "
                            + "AND organization_canonical = FALSE",
                    username));
            assertFalse(blockCanonical(firstBranch.hash()));
            assertTrue(blockCanonical(preferredFork.hash()));
        } finally {
            cleanup(username, organizationId, keyId, genesis, firstBranch, preferredFork);
        }
    }

    private static Block mineLowerHashFork(Block genesis, Block current) {
        for (int milliseconds = 2; milliseconds < 10_000; milliseconds++) {
            Instant timestamp = GENESIS_TIME.plusMillis(milliseconds);
            Block candidate = ProofOfWork.mine(
                    NETWORK_ID,
                    1,
                    genesis.hash(),
                    timestamp,
                    DIFFICULTY,
                    List.of(),
                    genesis.cumulativeWork());
            if (candidate.hash().compareTo(current.hash()) < 0) {
                return candidate;
            }
        }
        throw new IllegalStateException("Could not mine a deterministic lower-hash fork");
    }

    private static GovernanceTransaction registration(
            String organizationId,
            String keyId,
            KeyPair organization,
            KeyPair admin
    ) throws Exception {
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                UUID.randomUUID().toString(),
                GovernanceType.REGISTER_ORGANIZATION,
                EVENT_TIME,
                Map.of(
                        "organizationId", organizationId,
                        "organizationType", OrganizationType.FARMER.name(),
                        "name", "Block persistence integration farm",
                        "province", "Test Province",
                        "keyId", keyId,
                        "algorithm", "ECDSA_P256_SHA256",
                        "publicKey", Base64.getEncoder().encodeToString(organization.getPublic().getEncoded())),
                Base64.getEncoder().encodeToString(new byte[64]));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(admin.getPrivate());
        signer.update(GovernanceCodec.signingBytes(NETWORK_ID, unsigned));
        String signature = Base64.getEncoder().encodeToString(derToP1363(signer.sign()));
        GovernanceTransaction signed = new GovernanceTransaction(
                "pending",
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                unsigned.data(),
                signature);
        return new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, signed),
                signed.eventId(),
                signed.governanceType(),
                signed.eventTime(),
                signed.data(),
                signature);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] derToP1363(byte[] der) {
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

    private static void copyInteger(byte[] value, byte[] target, int targetOffset) {
        int sourceOffset = value.length > 32 && value[0] == 0 ? 1 : 0;
        int length = value.length - sourceOffset;
        if (length > 32) {
            throw new IllegalArgumentException("ECDSA signature integer is too large");
        }
        System.arraycopy(value, sourceOffset, target, targetOffset + 32 - length, length);
    }

    private static boolean isIsolatedDatabaseEmpty() throws Exception {
        for (String table : List.of(
                "blockchain_blocks", "blockchain_transactions", "organizations",
                "organization_keys", "authorized_peers", "batches", "batch_events",
                "users", "shipment_proposals")) {
            if (count("SELECT COUNT(*) FROM " + table) != 0) {
                return false;
            }
        }
        return true;
    }

    private static long count(String sql) throws Exception {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getLong(1);
        }
    }

    private static long countWhere(String sql, String value) throws Exception {
        try (Connection connection = DBConnection.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }

    private static long poolEntryCount(String transactionId) throws Exception {
        return countWhere("SELECT COUNT(*) FROM transaction_pool WHERE tx_id = ?", transactionId);
    }

    private static boolean blockCanonical(String blockHash) throws Exception {
        return countWhere(
                "SELECT COUNT(*) FROM blockchain_blocks WHERE block_hash = ? AND is_canonical = TRUE",
                blockHash) == 1;
    }

    private static void cleanup(
            String username,
            String organizationId,
            String keyId,
            Block genesis,
            Block firstBranch,
            Block preferredFork
    ) throws Exception {
        try (Connection connection = DBConnection.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM users WHERE username = ?")) {
                    statement.setString(1, username);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM authorized_peers WHERE organization_id = ?")) {
                    statement.setString(1, organizationId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM organization_keys WHERE key_id = ?")) {
                    statement.setString(1, keyId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM organizations WHERE organization_id = ?")) {
                    statement.setString(1, organizationId);
                    statement.executeUpdate();
                }
                if (firstBranch != null) {
                    deleteBlock(connection, firstBranch.hash());
                }
                if (preferredFork != null) {
                    deleteBlock(connection, preferredFork.hash());
                }
                deleteBlock(connection, genesis.hash());
                connection.commit();
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            }
        }
    }

    private static void deleteBlock(Connection connection, String blockHash) throws Exception {
        List<String> transactionIds = new java.util.ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT tx_id FROM block_transactions WHERE block_hash = ?")) {
            statement.setString(1, blockHash);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    transactionIds.add(result.getString("tx_id"));
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM block_transactions WHERE block_hash = ?")) {
            statement.setString(1, blockHash);
            statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM blockchain_blocks WHERE block_hash = ?")) {
            statement.setString(1, blockHash);
            statement.executeUpdate();
        }
        for (String transactionId : transactionIds) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM blockchain_transactions WHERE tx_id = ?")) {
                statement.setString(1, transactionId);
                statement.executeUpdate();
            }
        }
    }
}
