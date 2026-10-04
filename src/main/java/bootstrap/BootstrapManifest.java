package bootstrap;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Versioned, public-data-only description of a consortium's initial ledger. */
public record BootstrapManifest(
        int schemaVersion,
        String environment,
        String networkId,
        String genesisAdminPublicKey,
        Instant genesisTimestamp,
        long genesisNonce,
        int difficulty,
        String genesisHash,
        List<InitialBlock> initialBlocks,
        String signature
) {
    public BootstrapManifest {
        initialBlocks = List.copyOf(initialBlocks == null ? List.of() : initialBlocks);
    }

    public record InitialBlock(Instant timestamp, List<InitialGovernance> transactions, String hash) {
        public InitialBlock {
            transactions = List.copyOf(transactions == null ? List.of() : transactions);
        }
    }

    public record InitialGovernance(
            String eventId, String eventType, Instant eventTime,
            Map<String, String> data, String adminSignature
    ) {
        public InitialGovernance {
            data = Map.copyOf(data == null ? Map.of() : data);
        }
    }
}
