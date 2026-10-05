package bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import blockchain.BlockCodec;
import blockchain.ProofOfWork;
import config.NetworkConfiguration;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;

class BootstrapManifestCodecTest {
    @Test
    void signedGenesisManifestVerifiesAndWrongNetworkFails() throws Exception {
        KeyPair pair = keyPair();
        long nonce = 0;
        String hash;
        do {
            var header = new model.BlockHeader("test-net", 0, null, Instant.parse("2025-01-01T00:00:00Z"),
                    nonce, 1, BlockCodec.transactionsHash(List.of()));
            hash = BlockCodec.hashHeader(header);
            if (ProofOfWork.hasValidHash(hash, 1)) break;
            nonce++;
        } while (true);
        var unsigned = new BootstrapManifest(1, "development", "test-net",
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()),
                Instant.parse("2025-01-01T00:00:00Z"), nonce, 1, hash, List.of(), "AA==");
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(pair.getPrivate());
        signer.update(BootstrapManifestCodec.signingBytes(unsigned));
        var signed = new BootstrapManifest(unsigned.schemaVersion(), unsigned.environment(), unsigned.networkId(),
                unsigned.genesisAdminPublicKey(), unsigned.genesisTimestamp(), unsigned.genesisNonce(),
                unsigned.difficulty(), unsigned.genesisHash(), unsigned.initialBlocks(),
                Base64.getEncoder().encodeToString(signer.sign()));

        assertEquals(hash, BootstrapManifestCodec.verify(signed).tipHash());
        var tampered = new BootstrapManifest(1, "development", "other-net", signed.genesisAdminPublicKey(),
                signed.genesisTimestamp(), nonce, 1, hash, List.of(), signed.signature());
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifestCodec.verify(tampered));
    }

    @Test
    void rejectsMalformedAndUnexpectedManifestFields() {
        assertThrows(RuntimeException.class, () -> BootstrapManifestCodec.read("{"));
        assertThrows(IllegalArgumentException.class, () -> BootstrapManifestCodec.read(
                "{\"schemaVersion\":1,\"environment\":\"development\",\"networkId\":\"n\","
                + "\"genesisAdminPublicKey\":\"x\",\"genesisTimestamp\":\"2025-01-01T00:00:00Z\","
                + "\"genesisNonce\":0,\"difficulty\":1,\"genesisHash\":\""
                + "0000000000000000000000000000000000000000000000000000000000000000\","
                + "\"initialBlocks\":[],\"signature\":\"AA==\",\"unexpected\":true}"));
    }

    @Test
    void verifiesInitialBlocksAgainstConfiguredGenesisParent() throws Exception {
        KeyPair pair = keyPair();
        Instant genesisTime = Instant.parse("2025-01-01T00:00:00Z");
        String networkId = "bootstrap-block-test";
        var genesis = ProofOfWork.mine(networkId, 0, null, genesisTime, 1, List.of(), java.math.BigInteger.ZERO);
        var initial = ProofOfWork.mine(networkId, 1, genesis.hash(), genesisTime.plusSeconds(1), 1,
                List.of(), genesis.cumulativeWork());
        var unsigned = new BootstrapManifest(1, "development", networkId,
                Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()), genesisTime,
                genesis.header().nonce(), 1, genesis.hash(), List.of(
                new BootstrapManifest.InitialBlock(initial.header().timestamp(), List.of(), initial.hash())), "AA==");
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(pair.getPrivate()); signer.update(BootstrapManifestCodec.signingBytes(unsigned));
        var signed = new BootstrapManifest(unsigned.schemaVersion(), unsigned.environment(), unsigned.networkId(),
                unsigned.genesisAdminPublicKey(), unsigned.genesisTimestamp(), unsigned.genesisNonce(),
                unsigned.difficulty(), unsigned.genesisHash(), unsigned.initialBlocks(),
                Base64.getEncoder().encodeToString(signer.sign()));

        var verified = BootstrapManifestCodec.verify(signed);
        assertEquals(1, verified.expectedBlocks().size());
        assertEquals(initial.hash(), verified.tipHash());
    }

    private static KeyPair keyPair() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }
}
