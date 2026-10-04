package config;

import java.time.Instant;
import java.util.Base64;

public record NetworkConfiguration(
        String networkId,
        String genesisHash,
        int initialPowDifficulty,
        Instant genesisTimestamp,
        long genesisNonce,
        String genesisAdminPublicKey
) {
    public NetworkConfiguration {
        if (networkId == null || networkId.isBlank() || networkId.length() > 100) {
            throw new IllegalArgumentException("networkId must contain 1 to 100 characters");
        }
        if (genesisHash == null || !genesisHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("genesisHash must be a lowercase SHA-256 digest");
        }
        if (initialPowDifficulty < 1 || initialPowDifficulty > 16) {
            throw new IllegalArgumentException("initialPowDifficulty must be between 1 and 16");
        }
        if (genesisTimestamp == null || genesisTimestamp.getNano() % 1_000_000 != 0) {
            throw new IllegalArgumentException("genesisTimestamp must have millisecond precision");
        }
        if (genesisNonce < 0) {
            throw new IllegalArgumentException("genesisNonce must not be negative");
        }
        if (genesisAdminPublicKey == null || genesisAdminPublicKey.isBlank()) {
            throw new IllegalArgumentException("genesisAdminPublicKey must not be blank");
        }
        try {
            Base64.getDecoder().decode(genesisAdminPublicKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "genesisAdminPublicKey must be Base64-encoded", exception);
        }
    }

    public byte[] genesisAdminPublicKeyBytes() {
        return Base64.getDecoder().decode(genesisAdminPublicKey);
    }
}
