package security;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class PasswordHasher {
    private static final String FORMAT = "pbkdf2-sha256";
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int MIN_ITERATIONS = 600_000;
    private static final int MAX_ITERATIONS = 2_000_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;
    private static final int KEY_BITS = HASH_BYTES * Byte.SIZE;
    private static final int MAX_PASSWORD_CHARS = 1024;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecureRandom secureRandom;

    public PasswordHasher() {
        this(new SecureRandom());
    }

    PasswordHasher(SecureRandom secureRandom) {
        if (secureRandom == null) {
            throw new IllegalArgumentException("secureRandom must not be null");
        }
        this.secureRandom = secureRandom;
    }

    public String hash(char[] password) {
        requirePassword(password);
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        try {
            return FORMAT + "$" + ITERATIONS + "$" + ENCODER.encodeToString(salt)
                    + "$" + ENCODER.encodeToString(derived);
        } finally {
            java.util.Arrays.fill(derived, (byte) 0);
        }
    }

    public boolean verify(char[] password, String encodedHash) {
        requirePassword(password);
        if (encodedHash == null) {
            return false;
        }
        String[] parts = encodedHash.split("\\$", -1);
        if (parts.length != 4 || !FORMAT.equals(parts[0])) {
            return false;
        }

        int iterations;
        byte[] salt;
        byte[] expected;
        try {
            iterations = Integer.parseInt(parts[1]);
            salt = DECODER.decode(parts[2]);
            expected = DECODER.decode(parts[3]);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (iterations < MIN_ITERATIONS || iterations > MAX_ITERATIONS
                || salt.length != SALT_BYTES || expected.length != HASH_BYTES) {
            return false;
        }

        byte[] actual = derive(password, salt, iterations);
        try {
            return MessageDigest.isEqual(expected, actual);
        } finally {
            java.util.Arrays.fill(actual, (byte) 0);
            java.util.Arrays.fill(expected, (byte) 0);
        }
    }

    private static byte[] derive(char[] password, byte[] salt, int iterations) {
        PBEKeySpec keySpec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(keySpec).getEncoded();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Required password hashing algorithm is unavailable", exception);
        } finally {
            keySpec.clearPassword();
        }
    }

    private static void requirePassword(char[] password) {
        if (password == null || password.length == 0 || password.length > MAX_PASSWORD_CHARS) {
            throw new IllegalArgumentException("password must contain 1 to 1024 characters");
        }
    }
}
