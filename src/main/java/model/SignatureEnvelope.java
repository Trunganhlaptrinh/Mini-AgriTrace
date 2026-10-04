package model;

import java.util.Base64;

public record SignatureEnvelope(
        String organizationId,
        String keyId,
        String purpose,
        String signature
) {
    public SignatureEnvelope {
        requireText(organizationId, "organizationId");
        requireText(keyId, "keyId");
        requireText(purpose, "purpose");
        requireText(signature, "signature");
        try {
            if (Base64.getDecoder().decode(signature).length != 64) {
                throw new IllegalArgumentException("P-256 signature must contain exactly 64 bytes");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("signature must be Base64-encoded P-256 bytes", exception);
        }
    }

    public byte[] signatureBytes() {
        return Base64.getDecoder().decode(signature);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
