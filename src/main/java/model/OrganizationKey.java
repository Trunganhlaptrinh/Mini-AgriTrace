package model;

import java.util.Base64;

public record OrganizationKey(
        String keyId,
        String organizationId,
        String publicKey,
        long validFromHeight,
        Long revokedAtHeight
) {
    public OrganizationKey {
        requireText(keyId, "keyId");
        requireText(organizationId, "organizationId");
        requireText(publicKey, "publicKey");
        if (validFromHeight < 0) {
            throw new IllegalArgumentException("validFromHeight must not be negative");
        }
        if (revokedAtHeight != null && revokedAtHeight < validFromHeight) {
            throw new IllegalArgumentException("revokedAtHeight must not precede validFromHeight");
        }
        try {
            Base64.getDecoder().decode(publicKey);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("publicKey must be Base64-encoded SPKI bytes", exception);
        }
    }

    public byte[] publicKeyBytes() {
        return Base64.getDecoder().decode(publicKey);
    }

    public boolean isValidAt(long height) {
        return height >= validFromHeight && (revokedAtHeight == null || height < revokedAtHeight);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
