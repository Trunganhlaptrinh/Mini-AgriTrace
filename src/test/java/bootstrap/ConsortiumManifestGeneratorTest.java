package bootstrap;

import blockchain.SignatureUtil;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConsortiumManifestGeneratorTest {
    @Test
    void assemblesReproducibleThreeNodeManifestAndValidatesEveryLayer() throws Exception {
        KeyPair admin = keyPair();
        String descriptor = descriptor(admin);
        String requests1 = ConsortiumManifestGenerator.signingRequests(descriptor);
        String requests2 = ConsortiumManifestGenerator.signingRequests(descriptor);
        assertEquals(requests1, requests2);
        JsonObject requests = JsonParser.parseString(requests1).getAsJsonObject();
        assertEquals(6, requests.getAsJsonArray("requests").size());

        JsonObject signatureFile = new JsonObject();
        signatureFile.addProperty("schemaVersion", 1);
        JsonArray signatures = new JsonArray();
        for (var element : requests.getAsJsonArray("requests")) {
            JsonObject request = element.getAsJsonObject();
            byte[] bytes = Base64.getDecoder().decode(request.get("signingBytesBase64").getAsString());
            JsonObject item = new JsonObject();
            item.addProperty("eventId", request.get("eventId").getAsString());
            item.addProperty("signature", signP1363(admin, bytes));
            signatures.add(item);
        }
        signatureFile.add("signatures", signatures);
        String unsigned1 = ConsortiumManifestGenerator.assembleUnsignedManifest(descriptor, signatureFile.toString());
        String unsigned2 = ConsortiumManifestGenerator.assembleUnsignedManifest(descriptor, signatureFile.toString());
        assertEquals(unsigned1, unsigned2);
        BootstrapManifest unsigned = BootstrapManifestCodec.read(unsigned1);
        assertEquals(2, unsigned.initialBlocks().size());
        assertEquals(3, unsigned.initialBlocks().get(0).transactions().size());
        assertEquals(3, unsigned.initialBlocks().get(1).transactions().size());
        assertTrue(unsigned.signature().isEmpty());
        assertEquals("agritrace-local-3node-v1", unsigned.networkId());

        byte[] manifestSigningBytes = BootstrapManifestCodec.signingBytes(unsigned);
        String signed = ConsortiumManifestGenerator.finalizeManifest(unsigned1,
                signP1363(admin, manifestSigningBytes));
        var verified = BootstrapManifestCodec.verify(BootstrapManifestCodec.read(signed));
        assertEquals(3, verified.registry().organizations().size());
        assertEquals(3, verified.registry().organizationKeys().size());
        assertEquals(3, verified.registry().peers().size());
        assertEquals(2, verified.expectedBlocks().size());
        assertEquals(verified.expectedBlocks().get(1).hash(), verified.tipHash());
        assertTrue(verified.registry().peers().values().stream().allMatch(p -> p.active()));
        assertTrue(verified.registry().peers().values().stream().anyMatch(p -> p.endpoint().endsWith(":9443/AgriTrace")));
        assertFalse(signed.contains("PRIVATE KEY"));
    }

    @Test
    void refusesMissingOrInvalidGovernanceSignatures() throws Exception {
        KeyPair admin = keyPair();
        String descriptor = descriptor(admin);
        JsonObject signatures = new JsonObject();
        signatures.addProperty("schemaVersion", 1);
        signatures.add("signatures", new JsonArray());
        assertThrows(IllegalArgumentException.class,
                () -> ConsortiumManifestGenerator.assembleUnsignedManifest(descriptor, signatures.toString()));
    }

    @Test
    void rejectsManifestSignatureFromDifferentKey() throws Exception {
        KeyPair admin = keyPair();
        KeyPair other = keyPair();
        String descriptor = descriptor(admin);
        JsonObject requests = JsonParser.parseString(ConsortiumManifestGenerator.signingRequests(descriptor)).getAsJsonObject();
        JsonArray signatures = new JsonArray();
        for (var element : requests.getAsJsonArray("requests")) {
            JsonObject request = element.getAsJsonObject();
            JsonObject item = new JsonObject();
            item.addProperty("eventId", request.getAsJsonObject().get("eventId").getAsString());
            item.addProperty("signature", signP1363(admin,
                    Base64.getDecoder().decode(request.getAsJsonObject().get("signingBytesBase64").getAsString())));
            signatures.add(item);
        }
        JsonObject signatureFile = new JsonObject(); signatureFile.addProperty("schemaVersion", 1);
        signatureFile.add("signatures", signatures);
        String unsigned = ConsortiumManifestGenerator.assembleUnsignedManifest(descriptor, signatureFile.toString());
        assertThrows(IllegalArgumentException.class, () -> ConsortiumManifestGenerator.finalizeManifest(unsigned,
                signP1363(other, BootstrapManifestCodec.signingBytes(BootstrapManifestCodec.read(unsigned)))));
    }

    private static String descriptor(KeyPair admin) throws Exception {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("environment", "development");
        root.addProperty("networkId", "agritrace-local-3node-v1");
        root.addProperty("genesisAdminPublicKey", Base64.getEncoder().encodeToString(admin.getPublic().getEncoded()));
        root.addProperty("difficulty", 1);
        root.addProperty("genesisTimestamp", "2026-10-05T00:00:00.000Z");
        root.addProperty("governanceEventTime", "2026-10-05T00:00:01.000Z");
        root.addProperty("organizationBlockTimestamp", "2026-10-05T00:00:02.000Z");
        root.addProperty("peerBlockTimestamp", "2026-10-05T00:00:03.000Z");
        JsonArray organizations = new JsonArray();
        addOrganization(organizations, "org-a", "FARMER", "key-a");
        addOrganization(organizations, "org-b", "CARRIER", "key-b");
        addOrganization(organizations, "org-c", "RETAILER", "key-c");
        root.add("organizations", organizations);
        JsonArray peers = new JsonArray();
        addPeer(peers, "peer-a", "org-a", 9443, 'a');
        addPeer(peers, "peer-b", "org-b", 9444, 'b');
        addPeer(peers, "peer-c", "org-c", 9445, 'c');
        root.add("peers", peers);
        return root.toString();
    }

    private static void addOrganization(JsonArray list, String id, String type, String keyId) throws Exception {
        JsonObject item = new JsonObject(); item.addProperty("organizationId", id);
        item.addProperty("organizationType", type); item.addProperty("name", id);
        item.addProperty("keyId", keyId);
        item.addProperty("publicKey", Base64.getEncoder().encodeToString(keyPair().getPublic().getEncoded()));
        list.add(item);
    }

    private static void addPeer(JsonArray list, String id, String org, int port, char fingerprint) {
        JsonObject item = new JsonObject(); item.addProperty("peerId", id);
        item.addProperty("organizationId", org); item.addProperty("endpoint", "https://localhost:" + port + "/AgriTrace");
        item.addProperty("tlsCertificateFingerprint", String.valueOf(fingerprint).repeat(64)); list.add(item);
    }

    private static KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String signP1363(KeyPair key, byte[] bytes) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate()); signer.update(bytes);
        byte[] der = signer.sign();
        int offset = 2;
        if (der[offset++] != 0x02) throw new AssertionError("Expected DER r integer");
        int rLength = der[offset++] & 0xff;
        byte[] r = java.util.Arrays.copyOfRange(der, offset, offset + rLength); offset += rLength;
        if (der[offset++] != 0x02) throw new AssertionError("Expected DER s integer");
        int sLength = der[offset++] & 0xff;
        byte[] s = java.util.Arrays.copyOfRange(der, offset, offset + sLength);
        byte[] p1363 = new byte[64]; copyInteger(r, p1363, 0); copyInteger(s, p1363, 32);
        return Base64.getEncoder().encodeToString(p1363);
    }

    private static void copyInteger(byte[] encoded, byte[] target, int offset) {
        int source = encoded.length > 32 && encoded[0] == 0 ? 1 : 0;
        int length = encoded.length - source;
        System.arraycopy(encoded, source, target, offset + 32 - length, length);
    }
}