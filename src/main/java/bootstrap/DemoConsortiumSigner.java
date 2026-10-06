package bootstrap;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/**
 * Utility for local demo and test environments to sign governance requests
 * and bootstrap manifests using an administrative P-256 private key.
 */
public final class DemoConsortiumSigner {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private DemoConsortiumSigner() { }

    public static PrivateKey loadPrivateKey(Path path) throws IOException, GeneralSecurityException {
        byte[] bytes = Files.readAllBytes(path);
        String str = new String(bytes, StandardCharsets.UTF_8);
        byte[] der;
        if (str.contains("BEGIN")) {
            String pem = str
                    .replaceAll("-----[A-Z0-9 ]+-----", "")
                    .replaceAll("\\s+", "");
            der = Base64.getDecoder().decode(pem);
        } else {
            der = bytes;
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
        return KeyFactory.getInstance("EC").generatePrivate(keySpec);
    }

    public static String signP1363(PrivateKey key, byte[] data) throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(key);
        signer.update(data);
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    public static String signGovernanceRequests(String requestsJson, PrivateKey privateKey)
            throws GeneralSecurityException {
        JsonObject root = JsonParser.parseString(requestsJson).getAsJsonObject();
        JsonArray requests = root.getAsJsonArray("requests");
        if (requests == null) {
            throw new IllegalArgumentException("Requests array missing in signing requests document");
        }

        JsonObject outRoot = new JsonObject();
        outRoot.addProperty("schemaVersion", root.has("schemaVersion") ? root.get("schemaVersion").getAsInt() : 1);
        JsonArray signatures = new JsonArray();

        for (var el : requests) {
            JsonObject req = el.getAsJsonObject();
            String eventId = req.get("eventId").getAsString();
            String signingBytesB64 = req.get("signingBytesBase64").getAsString();
            byte[] signingBytes = Base64.getDecoder().decode(signingBytesB64);

            String sig = signP1363(privateKey, signingBytes);
            JsonObject item = new JsonObject();
            item.addProperty("eventId", eventId);
            item.addProperty("signature", sig);
            signatures.add(item);
        }

        outRoot.add("signatures", signatures);
        return JSON.toJson(outRoot) + System.lineSeparator();
    }

    public static String signManifest(String unsignedManifestJson, PrivateKey privateKey)
            throws GeneralSecurityException {
        BootstrapManifest manifest = BootstrapManifestCodec.read(unsignedManifestJson);
        byte[] signingBytes = BootstrapManifestCodec.signingBytes(manifest);
        return signP1363(privateKey, signingBytes);
    }

    public static void main(String[] args) {
        try {
            if (args.length == 4 && "sign-requests".equals(args[0])) {
                PrivateKey key = loadPrivateKey(Path.of(args[2]));
                String requestsJson = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
                String result = signGovernanceRequests(requestsJson, key);
                Files.writeString(Path.of(args[3]), result, StandardCharsets.UTF_8);
                return;
            }
            if (args.length == 4 && "sign-manifest".equals(args[0])) {
                PrivateKey key = loadPrivateKey(Path.of(args[2]));
                String manifestJson = Files.readString(Path.of(args[1]), StandardCharsets.UTF_8);
                String sig = signManifest(manifestJson, key);
                Files.writeString(Path.of(args[3]), sig, StandardCharsets.UTF_8);
                return;
            }
            throw new IllegalArgumentException(
                    "Usage: sign-requests <signing-requests.json> <admin-key.pkcs8> <output-signatures.json> " +
                    "| sign-manifest <unsigned-manifest.json> <admin-key.pkcs8> <output-signature.txt>");
        } catch (Exception ex) {
            System.err.println("Signing failed: " + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            System.exit(2);
        }
    }
}
