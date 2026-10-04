package blockchain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class HashUtil {
    private static final HexFormat LOWERCASE_HEX = HexFormat.of();

    private HashUtil() {
    }

    public static byte[] sha256(byte[] input) {
        if (input == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(input);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    public static String sha256Hex(byte[] input) {
        return LOWERCASE_HEX.formatHex(sha256(input));
    }

    public static String sha256Hex(String input) {
        if (input == null) {
            throw new IllegalArgumentException("Input must not be null");
        }
        return sha256Hex(input.getBytes(StandardCharsets.UTF_8));
    }
}
