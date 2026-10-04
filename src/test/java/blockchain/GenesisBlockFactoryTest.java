package blockchain;

import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import config.NetworkConfiguration;
import model.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GenesisBlockFactoryTest {
    @Test
    void reconstructsTheConfiguredGenesisExactly() throws Exception {
        Instant timestamp = Instant.parse("2026-10-04T10:00:00.000Z");
        Block mined = ProofOfWork.mine(
                "agritrace-test", 0, null, timestamp, 1, List.of(), java.math.BigInteger.ZERO);
        NetworkConfiguration configuration = configuration(mined.hash(), timestamp, mined.header().nonce());

        Block genesis = GenesisBlockFactory.configuredGenesis(configuration);

        assertEquals(mined, genesis);
    }

    @Test
    void rejectsConfiguredHashThatDoesNotMatchTheGenesisHeader() throws Exception {
        NetworkConfiguration configuration = configuration(
                "0".repeat(64), Instant.parse("2026-10-04T10:00:00.000Z"), 0);

        assertThrows(
                IllegalStateException.class,
                () -> GenesisBlockFactory.configuredGenesis(configuration));
    }

    private NetworkConfiguration configuration(String hash, Instant timestamp, long nonce)
            throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        String publicKey = Base64.getEncoder().encodeToString(generator.generateKeyPair()
                .getPublic().getEncoded());
        return new NetworkConfiguration(
                "agritrace-test", hash, 1, timestamp, nonce, publicKey);
    }
}
