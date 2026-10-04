package model;

public record PeerRegistration(
        String peerId,
        String organizationId,
        String endpoint,
        String tlsCertificateFingerprint,
        boolean active
) {
    public PeerRegistration {
        requireText(peerId, "peerId");
        requireText(organizationId, "organizationId");
        requireText(endpoint, "endpoint");
        if (tlsCertificateFingerprint == null
                || !tlsCertificateFingerprint.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(
                    "tlsCertificateFingerprint must be a lowercase SHA-256 hex digest");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
