package blockchain;

import java.util.List;
import config.NetworkConfiguration;
import model.Block;
import model.BlockHeader;

public final class GenesisBlockFactory {
    private GenesisBlockFactory() {
    }

    public static Block configuredGenesis(NetworkConfiguration configuration) {
        if (configuration == null) {
            throw new IllegalArgumentException("configuration must not be null");
        }
        BlockHeader header = new BlockHeader(
                configuration.networkId(),
                0,
                null,
                configuration.genesisTimestamp(),
                configuration.genesisNonce(),
                configuration.initialPowDifficulty(),
                BlockCodec.transactionsHash(List.of()));
        Block genesis = new Block(
                header,
                List.of(),
                ProofOfWork.workForDifficulty(configuration.initialPowDifficulty()),
                BlockCodec.hashHeader(header));
        if (!genesis.hash().equals(configuration.genesisHash())
                || !ProofOfWork.hasValidProof(genesis)) {
            throw new IllegalStateException(
                    "Configured genesis header does not match its hash or proof-of-work target");
        }
        return genesis;
    }
}
