package bootstrap;

import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.BlockValidationContext;
import dal.BlockDAO;
import dal.ConnectionProvider;
import dal.NetworkConfigDAO;
import dal.UserDAO;
import java.time.Clock;
import java.util.Objects;
import java.util.function.Consumer;
import security.PasswordHasher;

/** One-shot offline initializer. It only operates on an empty checked-in agritrace schema. */
public final class ConsortiumBootstrapService {
    private final ConnectionProvider connections;
    private final PasswordHasher passwordHasher;
    private final Consumer<BootstrapManifestCodec.Verified> localPeerVerifier;
    private final Consumer<BootstrapCheckpoint> checkpoint;
    private final String expectedCatalog;

    public ConsortiumBootstrapService(ConnectionProvider connections, PasswordHasher passwordHasher) {
        this(connections, passwordHasher, ConsortiumBootstrapService::requireLocalPeer, ignored -> { }, "agritrace");
    }

    /** Injection point for isolated tests; production CLI uses the certificate verifier above. */
    ConsortiumBootstrapService(ConnectionProvider connections, PasswordHasher passwordHasher,
                               Consumer<BootstrapManifestCodec.Verified> localPeerVerifier) {
        this(connections, passwordHasher, localPeerVerifier, ignored -> { }, "agritrace");
    }

    ConsortiumBootstrapService(ConnectionProvider connections, PasswordHasher passwordHasher,
                               Consumer<BootstrapManifestCodec.Verified> localPeerVerifier,
                               Consumer<BootstrapCheckpoint> checkpoint) {
        this(connections, passwordHasher, localPeerVerifier, checkpoint, "agritrace");
    }

    ConsortiumBootstrapService(ConnectionProvider connections, PasswordHasher passwordHasher,
                               Consumer<BootstrapManifestCodec.Verified> localPeerVerifier,
                               Consumer<BootstrapCheckpoint> checkpoint, String expectedCatalog) {
        this.connections = Objects.requireNonNull(connections, "connections");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.localPeerVerifier = Objects.requireNonNull(localPeerVerifier, "localPeerVerifier");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        this.expectedCatalog = Objects.requireNonNull(expectedCatalog, "expectedCatalog");
    }

    public BootstrapManifestCodec.Verified validate(String json) {
        return BootstrapManifestCodec.verify(BootstrapManifestCodec.read(json));
    }

    public void initialize(String json, String username, char[] adminPassword) {
        try (BootstrapLock ignored = BootstrapLock.acquire(connections)) {
            initializeInternal(json, username, adminPassword);
        } finally {
            if (adminPassword != null) java.util.Arrays.fill(adminPassword, '\0');
        }
    }

    private void initializeInternal(String json, String username, char[] adminPassword) {
        BootstrapManifestCodec.Verified bundle = validate(json);
        if (username == null || username.isBlank() || username.length() > 100)
            throw new IllegalArgumentException("A valid local ADMIN username is required");
        localPeerVerifier.accept(bundle);
        BootstrapStateVerifier stateVerifier = new BootstrapStateVerifier(connections, expectedCatalog);
        BootstrapStatus before = stateVerifier.inspect(bundle, username);
        if (before.state() == BootstrapStatus.State.INITIALIZED) return;
        if (before.state() != BootstrapStatus.State.UNINITIALIZED
                && before.state() != BootstrapStatus.State.RESUMABLE)
            throw new IllegalStateException("Bootstrap refused database state " + before.state()
                    + ": " + before.detail());
        if (before.localAdminUsername() == null && (adminPassword == null || adminPassword.length == 0))
            throw new IllegalArgumentException("A new local ADMIN password is required");
        if (before.state() == BootstrapStatus.State.UNINITIALIZED) {
            new NetworkConfigDAO(connections).installForBootstrap(bundle.network(),
                    bundle.manifestDigest(), bundle.environment());
            checkpoint.accept(new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_NETWORK_CONFIG, -1));
        }
        BlockValidator validator = new BlockValidator(bundle.network().networkId(),
                bundle.network().initialPowDifficulty(), bundle.network().genesisHash(),
                bundle.network().genesisAdminPublicKeyBytes());
        BlockDAO repository = new BlockDAO(connections, Clock.systemUTC(),
                bundle.network().networkId(), bundle.network().genesisHash());
        Blockchain chain = new Blockchain(validator, repository,
                BlockValidationContext.genesis(blockchain.GovernanceRegistry.empty()));
        int existingBlocks = before.blocksPresent();
        if (existingBlocks == 0) {
            chain.processBlock(bundle.genesis(), java.util.List.of());
            checkpoint.accept(new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_GENESIS, -1));
        }
        int firstManifestBlock = Math.max(0, existingBlocks - 1);
        for (int i = firstManifestBlock; i < bundle.expectedBlocks().size(); i++) {
            chain.processBlock(bundle.expectedBlocks().get(i), bundle.transactionsByBlock().get(i));
            checkpoint.accept(new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_INITIAL_BLOCK, i));
        }
        if (before.localAdminUsername() == null) {
            checkpoint.accept(new BootstrapCheckpoint(BootstrapCheckpoint.Stage.BEFORE_ADMIN, -1));
            String passwordHash = passwordHasher.hash(adminPassword);
            new UserDAO(connections).createUser(username, passwordHash, "ADMIN", null);
            checkpoint.accept(new BootstrapCheckpoint(BootstrapCheckpoint.Stage.AFTER_ADMIN, -1));
        }
        BootstrapStatus after = stateVerifier.inspect(bundle, username);
        if (after.state() != BootstrapStatus.State.INITIALIZED)
            throw new IllegalStateException("Bootstrap did not reach verified complete state: " + after.state());
    }

    public BootstrapStatus status(String json, String expectedAdminUsername) {
        try (BootstrapLock ignored = BootstrapLock.acquire(connections)) {
            BootstrapManifestCodec.Verified bundle = validate(json);
            BootstrapStatus dbStatus = new BootstrapStateVerifier(connections, expectedCatalog)
                    .inspect(bundle, expectedAdminUsername);
            boolean peerVerified = true;
            String detail = dbStatus.detail();
            try { localPeerVerifier.accept(bundle); }
            catch (RuntimeException e) { peerVerified = false; detail += "; local peer certificate is not verified"; }
            return new BootstrapStatus(dbStatus.state(), dbStatus.networkId(), dbStatus.blocksPresent(),
                    dbStatus.blocksExpected(), dbStatus.governanceTransactionsPresent(),
                    dbStatus.governanceTransactionsExpected(), dbStatus.localAdminUsername(), peerVerified, detail);
        }
    }

    private static void requireLocalPeer(BootstrapManifestCodec.Verified bundle) {
        config.PeerIdentityConfiguration configuration = config.PeerIdentityConfiguration.loadRequired();
        var peer = bundle.registry().peers().get(configuration.peerId());
        if (peer == null || !peer.active()
                || bundle.registry().organizations().get(peer.organizationId()) == null
                || bundle.registry().organizations().get(peer.organizationId()).status()
                    != model.OrganizationStatus.ACTIVE) {
            throw new IllegalStateException("Configured local peer must be active in the signed ledger");
        }
        char[] password = configuration.keyStorePassword();
        try {
            java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
            try (var input = java.nio.file.Files.newInputStream(configuration.keyStorePath())) {
                store.load(input, password);
            }
            java.security.cert.X509Certificate leaf = null;
            var aliases = store.aliases();
            while (aliases.hasMoreElements()) {
                String alias = aliases.nextElement();
                if (store.isKeyEntry(alias)) {
                    if (leaf != null) throw new IllegalStateException("Local PKCS#12 must contain exactly one private-key entry");
                    leaf = (java.security.cert.X509Certificate) store.getCertificate(alias);
                }
            }
            if (leaf == null) throw new IllegalStateException("Local PKCS#12 has no private-key certificate");
            String fingerprint = java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256").digest(leaf.getEncoded()));
            requirePeerFingerprint(peer, fingerprint);
        } catch (Exception e) {
            if (e instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("Could not verify configured local peer certificate", e);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
    }

    static void requirePeerFingerprint(model.PeerRegistration peer, String fingerprint) {
        if (peer == null || fingerprint == null || !fingerprint.equals(peer.tlsCertificateFingerprint()))
            throw new IllegalStateException("Local certificate does not match the signed peer registration");
    }
}
