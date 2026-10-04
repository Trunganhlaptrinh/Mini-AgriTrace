package config;

import java.nio.file.Path;

public final class PeerIdentityConfiguration {
    private final String peerId;
    private final Path keyStorePath;
    private final char[] keyStorePassword;

    public PeerIdentityConfiguration(String peerId, Path keyStorePath, char[] keyStorePassword) {
        if (peerId == null || peerId.isBlank()) {
            throw new IllegalArgumentException("peerId must not be blank");
        }
        if (keyStorePath == null || !keyStorePath.isAbsolute()) {
            throw new IllegalArgumentException("P2P PKCS#12 path must be absolute");
        }
        if (keyStorePassword == null || keyStorePassword.length == 0) {
            throw new IllegalArgumentException("P2P PKCS#12 password must not be blank");
        }
        this.peerId = peerId;
        this.keyStorePath = keyStorePath;
        this.keyStorePassword = keyStorePassword.clone();
    }

    public static PeerIdentityConfiguration loadRequired() {
        return new PeerIdentityConfiguration(
                required("agritrace.p2p.peer.id", "AGRITRACE_P2P_PEER_ID"),
                Path.of(required("agritrace.p2p.keystore.path", "AGRITRACE_P2P_KEYSTORE_PATH")),
                required("agritrace.p2p.keystore.password", "AGRITRACE_P2P_KEYSTORE_PASSWORD")
                        .toCharArray());
    }

    public String peerId() {
        return peerId;
    }

    public Path keyStorePath() {
        return keyStorePath;
    }

    public char[] keyStorePassword() {
        return keyStorePassword.clone();
    }

    private static String required(String property, String environmentVariable) {
        String value = System.getProperty(property);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing P2P configuration: " + property + " or " + environmentVariable);
        }
        return value;
    }
}
