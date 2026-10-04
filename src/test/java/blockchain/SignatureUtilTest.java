package blockchain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignatureUtilTest {
    @Test
    void verifiesWebCryptoP256SignatureAndPayloadHash() throws IOException, GeneralSecurityException {
        JsonObject vector = readVector();
        byte[] payload = vector.get("canonicalPayload").getAsString().getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getDecoder().decode(vector.get("signatureP1363Base64").getAsString());
        byte[] publicKey = Base64.getDecoder().decode(vector.get("publicKeySpkiBase64").getAsString());

        assertEquals(vector.get("canonicalPayload").getAsString(),
                CanonicalJson.canonicalize(vector.get("canonicalPayload").getAsString()));
        assertEquals(vector.get("payloadHashHex").getAsString(), HashUtil.sha256Hex(payload));
        assertTrue(SignatureUtil.verifyP256Sha256(payload, signature, publicKey));
    }

    @Test
    void rejectsSignatureForChangedPayload() throws IOException, GeneralSecurityException {
        JsonObject vector = readVector();
        byte[] payload = (vector.get("canonicalPayload").getAsString() + " ")
                .getBytes(StandardCharsets.UTF_8);
        byte[] signature = Base64.getDecoder().decode(vector.get("signatureP1363Base64").getAsString());
        byte[] publicKey = Base64.getDecoder().decode(vector.get("publicKeySpkiBase64").getAsString());

        assertFalse(SignatureUtil.verifyP256Sha256(payload, signature, publicKey));
    }

    @Test
    void rejectsMalformedP1363Signature() throws IOException {
        JsonObject vector = readVector();
        byte[] payload = vector.get("canonicalPayload").getAsString().getBytes(StandardCharsets.UTF_8);
        byte[] publicKey = Base64.getDecoder().decode(vector.get("publicKeySpkiBase64").getAsString());

        assertThrows(IllegalArgumentException.class,
                () -> SignatureUtil.verifyP256Sha256(payload, new byte[63], publicKey));
    }

    private JsonObject readVector() throws IOException {
        try (InputStream input = getClass().getResourceAsStream("/protocol-vectors.json")) {
            if (input == null) {
                throw new IOException("Missing protocol-vectors.json test resource");
            }
            return JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
        }
    }
}
