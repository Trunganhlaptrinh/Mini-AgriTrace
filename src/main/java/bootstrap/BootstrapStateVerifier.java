package bootstrap;

import blockchain.BlockRepository;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.BlockValidationContext;
import config.NetworkConfiguration;
import dal.BlockDAO;
import dal.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict, read-only recognizer for empty state and exact prefixes of a signed bundle. */
public final class BootstrapStateVerifier {
    private static final List<String> TABLES = List.of("network_config", "blockchain_transactions",
            "blockchain_blocks", "block_transactions", "transaction_pool", "node_transaction_status",
            "organizations", "organization_keys", "authorized_peers", "users", "shipment_proposals",
            "shipment_proposal_signatures", "batches", "batch_events");
    private final ConnectionProvider connections;
    private final String expectedCatalog;

    public BootstrapStateVerifier(ConnectionProvider connections) {
        this(connections, "agritrace");
    }

    public BootstrapStateVerifier(ConnectionProvider connections, String expectedCatalog) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.expectedCatalog = Objects.requireNonNull(expectedCatalog, "expectedCatalog");
    }

    public BootstrapStatus inspect(BootstrapManifestCodec.Verified bundle, String expectedAdminUsername) {
        Objects.requireNonNull(bundle, "bundle");
        try {
            Map<String, Long> counts = readCounts();
            if (counts.values().stream().allMatch(n -> n == 0))
                return status(BootstrapStatus.State.UNINITIALIZED, bundle, 0, 0, null,
                        "Initialized schema is empty");
            if (counts.get("network_config") == 0)
                return status(BootstrapStatus.State.UNEXPECTED_DATA, bundle, 0, 0, null,
                        "Application data exists without a network configuration");
            if (counts.get("network_config") != 1)
                return status(BootstrapStatus.State.INCONSISTENT, bundle, 0, 0, null,
                        "Network configuration singleton is inconsistent");

            NetworkConfiguration stored = readNetworkConfiguration();
            if (!bundle.network().equals(stored))
                return status(BootstrapStatus.State.DIFFERENT_NETWORK, bundle, 0, 0, null,
                        "Stored network configuration differs from the signed manifest");

            BlockValidator validator = new BlockValidator(stored.networkId(), stored.initialPowDifficulty(),
                    stored.genesisHash(), stored.genesisAdminPublicKeyBytes());
            BlockDAO blocks = new BlockDAO(connections, Clock.systemUTC(), stored.networkId(), stored.genesisHash());
            Blockchain chain = new Blockchain(validator, blocks,
                    BlockValidationContext.genesis(blockchain.GovernanceRegistry.empty()));
            List<BlockRepository.StoredBlock> canonical = chain.loadValidatedCanonicalChain();
            long allBlocks = counts.get("blockchain_blocks");
            if (allBlocks != canonical.size())
                return status(BootstrapStatus.State.UNEXPECTED_DATA, bundle, canonical.size(),
                        transactionCount(canonical), null, "Non-canonical or unrelated blocks exist");
            if (canonical.size() > bundle.expectedBlocks().size() + 1)
                return status(BootstrapStatus.State.DIFFERENT_NETWORK, bundle, canonical.size(),
                        transactionCount(canonical), null, "Stored chain extends beyond the manifest");
            if (!exactManifestPrefix(canonical, bundle))
                return status(BootstrapStatus.State.DIFFERENT_NETWORK, bundle, canonical.size(),
                        transactionCount(canonical), null, "Stored valid chain differs from the expected manifest prefix");

            int txCount = transactionCount(canonical);
            var snapshot = chain.loadCanonicalState();
            if (!expectedTableCounts(counts, canonical.size(), txCount,
                    snapshot.nextBlockContext().governanceRegistry()) || !allStatusesConfirmed())
                return status(BootstrapStatus.State.UNEXPECTED_DATA, bundle, canonical.size(), txCount,
                        null, "Application tables contain rows outside the expected bootstrap prefix");
            if (!projectionsMatch(snapshot.nextBlockContext().governanceRegistry(), canonical))
                return status(BootstrapStatus.State.INCONSISTENT, bundle, canonical.size(), txCount,
                        null, "Governance projections differ from validated canonical history");
            String admin = readAdmin(expectedAdminUsername);
            if (admin == null && counts.get("users") != 0)
                return status(BootstrapStatus.State.UNEXPECTED_DATA, bundle, canonical.size(), txCount,
                        null, "Local account state is not the expected single ADMIN account");

            boolean completeLedger = canonical.size() == bundle.expectedBlocks().size() + 1;
            if (completeLedger && admin != null)
                return status(BootstrapStatus.State.INITIALIZED, bundle, canonical.size(), txCount,
                        admin, "Database matches the complete signed bootstrap manifest");
            return status(BootstrapStatus.State.RESUMABLE, bundle, canonical.size(), txCount,
                    admin, "Database matches an exact bootstrap prefix and can be resumed");
        } catch (IllegalStateException e) {
            String message = e.getMessage() == null ? "Database state is invalid" : e.getMessage();
            BootstrapStatus.State state = message.contains("catalog")
                    ? BootstrapStatus.State.UNEXPECTED_DATA : BootstrapStatus.State.INCONSISTENT;
            return status(state, bundle, 0, 0, null, message);
        } catch (SQLException e) {
            return status(BootstrapStatus.State.INCONSISTENT, bundle, 0, 0, null,
                    "Database state could not be read consistently");
        } catch (RuntimeException e) {
            return status(BootstrapStatus.State.INCONSISTENT, bundle, 0, 0, null,
                    "Database state failed structural or consensus validation");
        }
    }

    private Map<String, Long> readCounts() {
        Map<String, Long> values = new HashMap<>();
        try (Connection c = connections.getConnection()) {
            requireCatalog(c);
            for (String table : TABLES) {
                try (PreparedStatement q = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet r = q.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("Could not inspect bootstrap target");
                    values.put(table, r.getLong(1));
                }
            }
            return values;
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap schema is missing or could not be inspected", e);
        }
    }

    private NetworkConfiguration readNetworkConfiguration() {
        try (Connection c = connections.getConnection()) {
            requireCatalog(c);
            try (PreparedStatement q = c.prepareStatement("""
                    SELECT network_id, genesis_hash, initial_pow_difficulty, genesis_timestamp,
                           genesis_nonce, genesis_admin_public_key FROM network_config WHERE id=1
                    """); ResultSet r = q.executeQuery()) {
                if (!r.next()) throw new IllegalStateException("Network configuration singleton is missing");
                return new NetworkConfiguration(r.getString(1), r.getString(2), r.getInt(3),
                        r.getTimestamp(4).toInstant(), r.getLong(5), r.getString(6));
            }
        } catch (SQLException | IllegalArgumentException e) {
            throw new IllegalStateException("Stored network configuration is invalid", e);
        }
    }

    private boolean projectionsMatch(blockchain.GovernanceRegistry registry,
                                     List<BlockRepository.StoredBlock> chain) throws SQLException {
        ProjectionOrigins origins = projectionOrigins(chain);
        try (Connection c = connections.getConnection()) {
            requireCatalog(c);
            Map<String, List<String>> organizations = new HashMap<>();
            try (var q = c.prepareStatement("SELECT organization_id, organization_type, name, province, status, registered_by_tx_id, updated_by_tx_id FROM organizations");
                 var r = q.executeQuery()) {
                while (r.next()) organizations.put(r.getString(1), List.of(r.getString(2), r.getString(3),
                        Objects.toString(r.getString(4), ""), r.getString(5),
                        Objects.toString(r.getString(6), ""), Objects.toString(r.getString(7), "")));
            }
            Map<String, List<String>> expectedOrganizations = new HashMap<>();
            registry.organizations().forEach((id, o) -> expectedOrganizations.put(id,
                    List.of(o.type().name(), o.name(), Objects.toString(o.province(), ""), o.status().name(),
                            origins.organizationRegistered.getOrDefault(id, ""),
                            origins.organizationUpdated.getOrDefault(id, ""))));
            if (!organizations.equals(expectedOrganizations)) return false;

            Map<String, List<String>> keys = new HashMap<>();
            try (var q = c.prepareStatement("SELECT key_id, organization_id, algorithm, public_key, valid_from_height, revoked_at_height, registered_by_tx_id, revoked_by_tx_id FROM organization_keys");
                 var r = q.executeQuery()) {
                while (r.next()) keys.put(r.getString(1), List.of(r.getString(2), r.getString(3), r.getString(4),
                        Long.toString(r.getLong(5)), Objects.toString(r.getObject(6), ""),
                        Objects.toString(r.getString(7), ""), Objects.toString(r.getString(8), "")));
            }
            Map<String, List<String>> expectedKeys = new HashMap<>();
            registry.organizationKeys().forEach((id, k) -> expectedKeys.put(id, List.of(k.organizationId(),
                    "ECDSA_P256_SHA256", k.publicKey(), Long.toString(k.validFromHeight()),
                    Objects.toString(k.revokedAtHeight(), ""),
                    origins.keyRegistered.getOrDefault(id, ""), origins.keyRevoked.getOrDefault(id, ""))));
            if (!keys.equals(expectedKeys)) return false;

            Map<String, List<String>> peers = new HashMap<>();
            try (var q = c.prepareStatement("SELECT peer_id, organization_id, endpoint, tls_certificate_fingerprint, status, registered_by_tx_id, updated_by_tx_id FROM authorized_peers");
                 var r = q.executeQuery()) {
                while (r.next()) peers.put(r.getString(1), List.of(r.getString(2), r.getString(3), r.getString(4),
                        r.getString(5), Objects.toString(r.getString(6), ""), Objects.toString(r.getString(7), "")));
            }
            Map<String, List<String>> expectedPeers = new HashMap<>();
            registry.peers().forEach((id, p) -> expectedPeers.put(id, List.of(p.organizationId(), p.endpoint(),
                    p.tlsCertificateFingerprint(), p.active() ? "ACTIVE" : "REVOKED",
                    origins.peerRegistered.getOrDefault(id, ""), origins.peerUpdated.getOrDefault(id, ""))));
            return peers.equals(expectedPeers);
        }
    }

    private static ProjectionOrigins projectionOrigins(List<BlockRepository.StoredBlock> chain) {
        ProjectionOrigins o = new ProjectionOrigins();
        for (BlockRepository.StoredBlock stored : chain) {
            for (model.LedgerTransaction ledger : stored.transactions()) {
                if (!(ledger instanceof model.GovernanceTransaction tx)) continue;
                switch (tx.governanceType()) {
                    case REGISTER_ORGANIZATION -> {
                        String id = tx.data().get("organizationId"), keyId = tx.data().get("keyId");
                        o.organizationRegistered.putIfAbsent(id, tx.transactionId());
                        o.organizationUpdated.put(id, tx.transactionId());
                        o.keyRegistered.putIfAbsent(keyId, tx.transactionId());
                    }
                    case REGISTER_ORGANIZATION_KEY -> o.keyRegistered.putIfAbsent(
                            tx.data().get("keyId"), tx.transactionId());
                    case REVOKE_ORGANIZATION_KEY -> o.keyRevoked.put(tx.data().get("keyId"), tx.transactionId());
                    case SET_ORGANIZATION_STATUS -> o.organizationUpdated.put(
                            tx.data().get("organizationId"), tx.transactionId());
                    case REGISTER_PEER -> {
                        String id = tx.data().get("peerId");
                        o.peerRegistered.putIfAbsent(id, tx.transactionId());
                        o.peerUpdated.put(id, tx.transactionId());
                    }
                    case REVOKE_PEER -> o.peerUpdated.put(tx.data().get("peerId"), tx.transactionId());
                }
            }
        }
        return o;
    }

    private static final class ProjectionOrigins {
        private final Map<String, String> organizationRegistered = new HashMap<>();
        private final Map<String, String> organizationUpdated = new HashMap<>();
        private final Map<String, String> keyRegistered = new HashMap<>();
        private final Map<String, String> keyRevoked = new HashMap<>();
        private final Map<String, String> peerRegistered = new HashMap<>();
        private final Map<String, String> peerUpdated = new HashMap<>();
    }

    private String readAdmin(String expectedUsername) throws SQLException {
        try (Connection c = connections.getConnection()) {
            requireCatalog(c);
            try (var q = c.prepareStatement("SELECT username, role, organization_id, is_active, organization_canonical, password_hash FROM users");
                 var r = q.executeQuery()) {
                if (!r.next()) return null;
                String username = r.getString(1);
                String passwordHash = r.getString(6);
                if (!"ADMIN".equals(r.getString(2)) || r.getString(3) != null || !r.getBoolean(4)
                        || !r.getBoolean(5) || r.getString(6) == null || r.getString(6).isBlank()
                        || !isSupportedPasswordHash(passwordHash)
                        || (expectedUsername != null && !expectedUsername.equals(username)) || r.next()) return null;
                return username;
            }
        }
    }

    private static boolean isSupportedPasswordHash(String encoded) {
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2-sha256".equals(parts[0])) return false;
        try {
            int iterations = Integer.parseInt(parts[1]);
            byte[] salt = java.util.Base64.getUrlDecoder().decode(parts[2]);
            byte[] hash = java.util.Base64.getUrlDecoder().decode(parts[3]);
            return iterations >= 600_000 && iterations <= 2_000_000 && salt.length == 16 && hash.length == 32;
        } catch (IllegalArgumentException e) { return false; }
    }

    private boolean expectedTableCounts(Map<String, Long> c, int blockCount, int txCount,
                                        blockchain.GovernanceRegistry registry) {
        return c.get("network_config") == 1 && c.get("blockchain_blocks") == blockCount
                && c.get("block_transactions") == txCount && c.get("blockchain_transactions") == txCount
                && c.get("transaction_pool") == 0 && c.get("node_transaction_status") == txCount
                && c.get("organizations") == registry.organizations().size()
                && c.get("organization_keys") == registry.organizationKeys().size()
                && c.get("authorized_peers") == registry.peers().size()
                && c.get("batches") == 0 && c.get("batch_events") == 0
                && c.get("shipment_proposals") == 0 && c.get("shipment_proposal_signatures") == 0;
    }

    private boolean allStatusesConfirmed() throws SQLException {
        try (Connection c = connections.getConnection()) {
            requireCatalog(c);
            try (var q = c.prepareStatement(
                    "SELECT COUNT(*) FROM node_transaction_status WHERE status <> 'CONFIRMED' OR rejection_code IS NOT NULL");
                 var r = q.executeQuery()) {
                return r.next() && r.getLong(1) == 0;
            }
        }
    }

    static boolean exactManifestPrefix(List<BlockRepository.StoredBlock> chain,
                                       BootstrapManifestCodec.Verified bundle) {
        if (chain.size() > bundle.expectedBlocks().size() + 1) return false;
        for (int i = 0; i < chain.size(); i++) {
            model.Block expected = i == 0 ? bundle.genesis() : bundle.expectedBlocks().get(i - 1);
            if (!expected.equals(chain.get(i).block())) return false;
            List<String> expectedIds = i == 0 ? List.of()
                    : bundle.transactionsByBlock().get(i - 1).stream()
                        .map(model.GovernanceTransaction::transactionId).toList();
            if (!expectedIds.equals(chain.get(i).transactions().stream()
                    .map(model.LedgerTransaction::transactionId).toList())) return false;
        }
        return true;
    }

    private static int transactionCount(List<BlockRepository.StoredBlock> chain) {
        return chain.stream().mapToInt(s -> s.transactions().size()).sum();
    }

    private void requireCatalog(Connection c) throws SQLException {
        if (!expectedCatalog.equalsIgnoreCase(c.getCatalog()))
            throw new IllegalStateException("Bootstrap target catalog does not match the configured catalog");
    }

    private static BootstrapStatus status(BootstrapStatus.State state,
            BootstrapManifestCodec.Verified bundle, int blocks, int txs, String admin, String detail) {
        return new BootstrapStatus(state, bundle.network().networkId(), blocks,
                bundle.expectedBlocks().size() + 1,
                txs, bundle.transactionsByBlock().stream().mapToInt(List::size).sum(), admin, false, detail);
    }
}
