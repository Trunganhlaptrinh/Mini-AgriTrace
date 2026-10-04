package bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import blockchain.BlockCodec;
import blockchain.BlockRepository;
import blockchain.ProofOfWork;
import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import model.Block;
import org.junit.jupiter.api.Test;

class BootstrapStateVerifierTest {
    @Test
    void exactPrefixMatcherAcceptsOnlyManifestGenesisAndRejectsChangedHeader() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        var pair = generator.generateKeyPair();
        Instant timestamp = Instant.parse("2025-01-01T00:00:00Z");
        Block genesis = ProofOfWork.mine("prefix-test", 0, null, timestamp, 1, List.of(), BigInteger.ZERO);
        var unsigned = new BootstrapManifest(1, "development", "prefix-test",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), timestamp,
                genesis.header().nonce(), 1, genesis.hash(), List.of(), "AA==");
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(pair.getPrivate()); signer.update(BootstrapManifestCodec.signingBytes(unsigned));
        var signed = new BootstrapManifest(unsigned.schemaVersion(), unsigned.environment(), unsigned.networkId(),
                unsigned.genesisAdminPublicKey(), unsigned.genesisTimestamp(), unsigned.genesisNonce(),
                unsigned.difficulty(), unsigned.genesisHash(), List.of(),
                Base64.getEncoder().encodeToString(signer.sign()));
        var bundle = BootstrapManifestCodec.verify(signed);
        var storedGenesis = new BlockRepository.StoredBlock(genesis, List.of());

        assertTrue(BootstrapStateVerifier.exactManifestPrefix(List.of(), bundle));
        assertTrue(BootstrapStateVerifier.exactManifestPrefix(List.of(storedGenesis), bundle));
        assertFalse(BootstrapStateVerifier.exactManifestPrefix(List.of(storedGenesis, storedGenesis), bundle));

        var alteredHeader = new model.BlockHeader(genesis.header().networkId(), 0, null,
                genesis.header().timestamp(), genesis.header().nonce() + 1, genesis.header().difficulty(),
                BlockCodec.transactionsHash(List.of()));
        var corrupted = new Block(alteredHeader, List.of(), genesis.cumulativeWork(), genesis.hash());
        assertFalse(BootstrapStateVerifier.exactManifestPrefix(
                List.of(new BlockRepository.StoredBlock(corrupted, List.of())), bundle));
    }

    @Test
    void rejectsLocalCertificateFingerprintThatDiffersFromPeerRegistration() {
        var peer = new model.PeerRegistration("peer", "org", "https://localhost:8443", "a".repeat(64), true);
        assertThrows(IllegalStateException.class,
                () -> ConsortiumBootstrapService.requirePeerFingerprint(peer, "b".repeat(64)));
        assertDoesNotThrow(() -> ConsortiumBootstrapService.requirePeerFingerprint(peer, "a".repeat(64)));
    }
}
