package bootstrap;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import blockchain.GovernanceCodec;
import blockchain.ProofOfWork;
import dal.ConnectionProvider;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import model.GovernanceTransaction;
import model.GovernanceType;
import org.junit.jupiter.api.Test;
import security.PasswordHasher;

class ConsortiumBootstrapMySqlIntegrationTest {
    private record Fixture(String json, BootstrapManifestCodec.Verified verified,
                           String organizationId, String keyId, String peerId, String adminName) { }

    @Test
    void resumesEveryDurableBoundaryAndRejectsConflictingState() throws Exception {
        assumeTrue("true".equalsIgnoreCase(System.getenv("AGRITRACE_BOOTSTRAP_IT_ENABLED")),
                "Dedicated bootstrap DB test is opt-in; see docs/AI/CONSORTIUM_BOOTSTRAP_USAGE.md");
        BootstrapIntegrationDatabase database = BootstrapIntegrationDatabase.requireConfigured();
        ConnectionProvider connections = database.connections();
        var stages = List.of(
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_NETWORK_CONFIG, -1),
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_GENESIS, -1),
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_INITIAL_BLOCK, 0),
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_INITIAL_BLOCK, 1),
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.BEFORE_ADMIN, -1),
                new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_ADMIN, -1));
        for (BootstrapCheckpoint target : stages) {
            Fixture fixture = fixture();
            try {
                var verifier = new BootstrapStateVerifier(connections);
                assertEquals(BootstrapStatus.State.UNINITIALIZED, verifier.inspect(fixture.verified(), null).state(),
                        "Dedicated DB must be empty before each recovery scenario");
                AtomicBoolean interrupted = new AtomicBoolean();
                ConsortiumBootstrapService crashing = new ConsortiumBootstrapService(connections,
                        new PasswordHasher(), ignored -> { }, checkpoint -> {
                            if (!interrupted.get() && target.equals(checkpoint)
                                    && interrupted.compareAndSet(false, true)) throw new SimulatedInterruption();
                        });
                assertThrows(SimulatedInterruption.class,
                        () -> crashing.initialize(fixture.json(), fixture.adminName(), "test-password-1".toCharArray()));
                assertTrue(interrupted.get());

                BootstrapStatus prefix = crashing.status(fixture.json(), fixture.adminName());
                assertTrue(prefix.state() == BootstrapStatus.State.RESUMABLE
                                || (target.stage() == BootstrapCheckpoint.Stage.AFTER_ADMIN
                                    && prefix.state() == BootstrapStatus.State.INITIALIZED),
                        prefix.toString());
                assertTrue(prefix.localPeerVerified());

                ConsortiumBootstrapService resume = new ConsortiumBootstrapService(connections,
                        new PasswordHasher(), ignored -> { });
                resume.initialize(fixture.json(), fixture.adminName(), "test-password-2".toCharArray());
                BootstrapStatus complete = verifier.inspect(fixture.verified(), fixture.adminName());
                assertEquals(BootstrapStatus.State.INITIALIZED, complete.state(), complete.toString());
                assertEquals(complete.blocksExpected(), complete.blocksPresent());
                assertEquals(complete.governanceTransactionsExpected(), complete.governanceTransactionsPresent());
                resume.initialize(fixture.json(), fixture.adminName(), null);
                BootstrapStatus repeated = verifier.inspect(fixture.verified(), fixture.adminName());
                assertEquals(BootstrapStatus.State.INITIALIZED, repeated.state());
                assertEquals(complete.blocksPresent(), repeated.blocksPresent());
                assertEquals(complete.governanceTransactionsPresent(), repeated.governanceTransactionsPresent());

                verifyRejectedStates(connections, fixture);
            } finally {
                cleanup(connections, fixture);
            }
        }
    }

    private static void verifyRejectedStates(ConnectionProvider connections, Fixture f) throws Exception {
        var verifier = new BootstrapStateVerifier(connections);
        Fixture other = fixture();
        assertEquals(BootstrapStatus.State.DIFFERENT_NETWORK,
                verifier.inspect(other.verified(), null).state());

        try (Connection c = connections.getConnection(); PreparedStatement u = c.prepareStatement(
                "UPDATE blockchain_blocks SET nonce=nonce+1 WHERE block_hash=?")) {
            u.setString(1, f.verified().expectedBlocks().get(0).hash());
            assertEquals(1, u.executeUpdate());
        }
        assertEquals(BootstrapStatus.State.INCONSISTENT, verifier.inspect(f.verified(), f.adminName()).state());
        try (Connection c = connections.getConnection(); PreparedStatement u = c.prepareStatement(
                "UPDATE blockchain_blocks SET nonce=? WHERE block_hash=?")) {
            u.setLong(1, f.verified().expectedBlocks().get(0).header().nonce());
            u.setString(2, f.verified().expectedBlocks().get(0).hash());
            assertEquals(1, u.executeUpdate());
        }

        try (Connection c = connections.getConnection(); PreparedStatement u = c.prepareStatement(
                "UPDATE authorized_peers SET tls_certificate_fingerprint=? WHERE peer_id=?")) {
            u.setString(1, "f".repeat(64)); u.setString(2, f.peerId());
            assertEquals(1, u.executeUpdate());
        }
        assertEquals(BootstrapStatus.State.INCONSISTENT, verifier.inspect(f.verified(), f.adminName()).state());
        try (Connection c = connections.getConnection(); PreparedStatement u = c.prepareStatement(
                "UPDATE authorized_peers SET tls_certificate_fingerprint=? WHERE peer_id=?")) {
            u.setString(1, f.verified().registry().peers().get(f.peerId()).tlsCertificateFingerprint());
            u.setString(2, f.peerId()); assertEquals(1, u.executeUpdate());
        }

        try (Connection c = connections.getConnection(); PreparedStatement i = c.prepareStatement(
                "INSERT INTO users (username,password_hash,role,organization_id) VALUES (?,?,'ADMIN',NULL)")) {
            i.setString(1, f.adminName() + "-unexpected"); i.setString(2, "test-only-hash"); i.executeUpdate();
        }
        assertEquals(BootstrapStatus.State.UNEXPECTED_DATA, verifier.inspect(f.verified(), f.adminName()).state());
        try (Connection c = connections.getConnection(); PreparedStatement d = c.prepareStatement(
                "DELETE FROM users WHERE username=?")) {
            d.setString(1, f.adminName() + "-unexpected"); assertEquals(1, d.executeUpdate());
        }
        assertEquals(BootstrapStatus.State.INITIALIZED, verifier.inspect(f.verified(), f.adminName()).state());
    }

    private static Fixture fixture() throws Exception {
        String suffix = UUID.randomUUID().toString();
        String networkId = "bootstrap-it-" + suffix;
        String organizationId = "org-" + suffix;
        String keyId = "key-" + suffix;
        String peerId = "peer-" + suffix;
        String adminName = "admin-" + suffix;
        KeyPair admin = keyPair(), organization = keyPair();
        Instant genesisTime = Instant.parse("2026-01-01T00:00:00Z");
        var genesis = ProofOfWork.mine(networkId, 0, null, genesisTime, 1, List.of(), BigInteger.ZERO);
        var organizationTx = governance(admin, networkId, "register-org-" + suffix,
                GovernanceType.REGISTER_ORGANIZATION, genesisTime.plusMillis(1000), Map.of(
                        "organizationId", organizationId, "organizationType", "FARMER", "name", "Bootstrap IT org",
                        "keyId", keyId, "algorithm", "ECDSA_P256_SHA256",
                        "publicKey", Base64.getEncoder().encodeToString(organization.getPublic().getEncoded())));
        var block1 = ProofOfWork.mine(networkId, 1, genesis.hash(), genesisTime.plusMillis(1000), 1,
                List.of(organizationTx.transactionId()), genesis.cumulativeWork());
        var peerTx = governance(admin, networkId, "register-peer-" + suffix,
                GovernanceType.REGISTER_PEER, genesisTime.plusMillis(2000), Map.of(
                        "peerId", peerId, "organizationId", organizationId,
                        "endpoint", "https://localhost:18443", "tlsCertificateFingerprint", "a".repeat(64)));
        var block2 = ProofOfWork.mine(networkId, 2, block1.hash(), genesisTime.plusMillis(2000), 1,
                List.of(peerTx.transactionId()), block1.cumulativeWork());
        var unsigned = new BootstrapManifest(1, "development", networkId,
                Base64.getEncoder().encodeToString(admin.getPublic().getEncoded()), genesisTime,
                genesis.header().nonce(), 1, genesis.hash(), List.of(
                new BootstrapManifest.InitialBlock(block1.header().timestamp(), List.of(toSpec(organizationTx)), block1.hash()),
                new BootstrapManifest.InitialBlock(block2.header().timestamp(), List.of(toSpec(peerTx)), block2.hash())), "AA==");
        Signature signature = Signature.getInstance("SHA256withECDSAinP1363Format");
        signature.initSign(admin.getPrivate()); signature.update(BootstrapManifestCodec.signingBytes(unsigned));
        BootstrapManifest signed = new BootstrapManifest(unsigned.schemaVersion(), unsigned.environment(),
                unsigned.networkId(), unsigned.genesisAdminPublicKey(), unsigned.genesisTimestamp(),
                unsigned.genesisNonce(), unsigned.difficulty(), unsigned.genesisHash(), unsigned.initialBlocks(),
                Base64.getEncoder().encodeToString(signature.sign()));
        String json = BootstrapManifestCodec.toJson(signed);
        return new Fixture(json, BootstrapManifestCodec.verify(signed), organizationId, keyId, peerId, adminName);
    }

    private static BootstrapManifest.InitialGovernance toSpec(GovernanceTransaction tx) {
        return new BootstrapManifest.InitialGovernance(tx.eventId(), tx.governanceType().name(),
                tx.eventTime(), tx.data(), tx.adminSignature());
    }

    private static GovernanceTransaction governance(KeyPair admin, String network, String event,
            GovernanceType type, Instant time, Map<String, String> data) throws Exception {
        String placeholder = Base64.getEncoder().encodeToString(new byte[64]);
        var draft = new GovernanceTransaction("0".repeat(64), event, type, time, data, placeholder);
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(admin.getPrivate()); signer.update(GovernanceCodec.signingBytes(network, draft));
        String signature = Base64.getEncoder().encodeToString(signer.sign());
        var signedDraft = new GovernanceTransaction("0".repeat(64), event, type, time, data, signature);
        return new GovernanceTransaction(GovernanceCodec.transactionId(network, signedDraft), event,
                type, time, data, signature);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1")); return generator.generateKeyPair();
    }

    private static void cleanup(ConnectionProvider connections, Fixture f) throws Exception {
        List<String> txIds = f.verified().transactionsByBlock().stream().flatMap(List::stream)
                .map(GovernanceTransaction::transactionId).toList();
        List<String> blockHashes = new ArrayList<>();
        blockHashes.add(f.verified().genesis().hash());
        f.verified().expectedBlocks().forEach(b -> blockHashes.add(b.hash()));
        try (Connection c = connections.getConnection()) {
            c.setAutoCommit(false);
            try {
                deleteBy(c, "DELETE FROM authorized_peers WHERE peer_id=?", f.peerId());
                deleteBy(c, "DELETE FROM organization_keys WHERE key_id=?", f.keyId());
                deleteBy(c, "DELETE FROM organizations WHERE organization_id=?", f.organizationId());
                deleteBy(c, "DELETE FROM users WHERE username=?", f.adminName());
                for (String tx : txIds) {
                    deleteBy(c, "DELETE FROM transaction_pool WHERE tx_id=?", tx);
                    deleteBy(c, "DELETE FROM node_transaction_status WHERE tx_id=?", tx);
                }
                try (PreparedStatement d = c.prepareStatement("DELETE FROM block_transactions WHERE block_hash=?")) {
                    for (String hash : blockHashes) { d.setString(1, hash); d.addBatch(); }
                    d.executeBatch();
                }
                for (int i = blockHashes.size() - 1; i >= 0; i--)
                    deleteBy(c, "DELETE FROM blockchain_blocks WHERE block_hash=?", blockHashes.get(i));
                for (String tx : txIds) deleteBy(c, "DELETE FROM blockchain_transactions WHERE tx_id=?", tx);
                deleteBy(c, "DELETE FROM network_config WHERE id=1 AND network_id=?", f.verified().network().networkId());
                c.commit();
            } catch (Exception e) { c.rollback(); throw e; }
        }
    }

    private static void deleteBy(Connection c, String sql, String value) throws Exception {
        try (PreparedStatement d = c.prepareStatement(sql)) { d.setString(1, value); d.executeUpdate(); }
    }

    private static final class SimulatedInterruption extends RuntimeException { }
}
