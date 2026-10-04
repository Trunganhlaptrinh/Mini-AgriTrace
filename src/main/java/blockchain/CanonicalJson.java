package blockchain;

import java.io.IOException;
import org.erdtman.jcs.JsonCanonicalizer;

public final class CanonicalJson {
    private CanonicalJson() {
    }

    public static String canonicalize(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON must not be null or blank");
        }
        try {
            return new JsonCanonicalizer(json).getEncodedString();
        } catch (IOException exception) {
            throw new IllegalArgumentException("JSON cannot be canonicalized", exception);
        }
    }
}
