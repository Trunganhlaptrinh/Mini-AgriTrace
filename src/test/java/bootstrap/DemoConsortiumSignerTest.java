package bootstrap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class DemoConsortiumSignerTest {

    @Test
    void signsRequestsAndManifestCleanly(@TempDir Path tempDir) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair admin = kpg.generateKeyPair();

        // Write PKCS#8 PEM
        String pem = "-----BEGIN PRIVATE KEY-----\n" +
                Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(admin.getPrivate().getEncoded()) +
                "\n-----END PRIVATE KEY-----\n";
        Path keyFile = tempDir.resolve("admin.key");
        Files.writeString(keyFile, pem);

        String adminPubSpki = Base64.getEncoder().encodeToString(admin.getPublic().getEncoded());

        // Full 3-node descriptor required by ConsortiumManifestGenerator
        JsonObject desc = new JsonObject();
        desc.addProperty("schemaVersion", 1);
        desc.addProperty("environment", "development");
        desc.addProperty("networkId", "agritrace-demo-test-v1");
        desc.addProperty("genesisAdminPublicKey", adminPubSpki);
        desc.addProperty("difficulty", 1);
        desc.addProperty("genesisTimestamp", "2026-10-05T00:00:00.000Z");
        desc.addProperty("governanceEventTime", "2026-10-05T00:00:01.000Z");
        desc.addProperty("organizationBlockTimestamp", "2026-10-05T00:00:02.000Z");
        desc.addProperty("peerBlockTimestamp", "2026-10-05T00:00:03.000Z");

        JsonArray orgs = new JsonArray();
        addOrg(kpg, orgs, "org-farmer-a", "FARMER", "Demo Farmer", "key-farmer-1");
        addOrg(kpg, orgs, "org-carrier-b", "CARRIER", "Demo Carrier", "key-carrier-1");
        addOrg(kpg, orgs, "org-retailer-c", "RETAILER", "Demo Retailer", "key-retailer-1");
        desc.add("organizations", orgs);

        JsonArray peers = new JsonArray();
        addPeer(peers, "peer-farmer-a", "org-farmer-a", 9443, 'a');
        addPeer(peers, "peer-carrier-b", "org-carrier-b", 9444, 'b');
        addPeer(peers, "peer-retailer-c", "org-retailer-c", 9445, 'c');
        desc.add("peers", peers);

        String descriptorJson = desc.toString();
        String reqsJson = ConsortiumManifestGenerator.signingRequests(descriptorJson);

        Path reqsFile = tempDir.resolve("requests.json");
        Files.writeString(reqsFile, reqsJson);

        Path sigsFile = tempDir.resolve("signatures.json");
        DemoConsortiumSigner.main(new String[]{
                "sign-requests",
                reqsFile.toString(),
                keyFile.toString(),
                sigsFile.toString()
        });
        assertTrue(Files.exists(sigsFile));

        String sigsJson = Files.readString(sigsFile);
        String unsignedManifestJson = ConsortiumManifestGenerator.assembleUnsignedManifest(descriptorJson, sigsJson);

        Path unsignedFile = tempDir.resolve("unsigned-manifest.json");
        Files.writeString(unsignedFile, unsignedManifestJson);

        Path manifestSigFile = tempDir.resolve("manifest-sig.txt");
        DemoConsortiumSigner.main(new String[]{
                "sign-manifest",
                unsignedFile.toString(),
                keyFile.toString(),
                manifestSigFile.toString()
        });
        assertTrue(Files.exists(manifestSigFile));

        String manifestSig = Files.readString(manifestSigFile).trim();
        String signedManifestJson = ConsortiumManifestGenerator.finalizeManifest(unsignedManifestJson, manifestSig);

        BootstrapManifest manifest = BootstrapManifestCodec.read(signedManifestJson);
        var verified = BootstrapManifestCodec.verify(manifest);
        assertNotNull(verified);
        assertEquals("agritrace-demo-test-v1", verified.network().networkId());
    }

    private static void addOrg(KeyPairGenerator kpg, JsonArray list, String id, String type, String name, String keyId) {
        JsonObject item = new JsonObject();
        item.addProperty("organizationId", id);
        item.addProperty("organizationType", type);
        item.addProperty("name", name);
        item.addProperty("keyId", keyId);
        item.addProperty("publicKey", Base64.getEncoder().encodeToString(kpg.generateKeyPair().getPublic().getEncoded()));
        list.add(item);
    }

    private static void addPeer(JsonArray list, String id, String orgId, int port, char fingerprintChar) {
        JsonObject item = new JsonObject();
        item.addProperty("peerId", id);
        item.addProperty("organizationId", orgId);
        item.addProperty("endpoint", "https://localhost:" + port + "/AgriTrace");
        item.addProperty("tlsCertificateFingerprint", String.valueOf(fingerprintChar).repeat(64));
        list.add(item);
    }
}
