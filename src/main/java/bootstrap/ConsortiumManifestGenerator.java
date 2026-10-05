package bootstrap;

import blockchain.BlockValidationContext;
import blockchain.BlockValidator;
import blockchain.GovernanceCodec;
import blockchain.GovernanceRegistry;
import blockchain.ProofOfWork;
import blockchain.SignatureUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import config.NetworkConfiguration;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import model.Block;
import model.GovernanceTransaction;
import model.GovernanceType;

/** Deterministically assembles the existing signed bootstrap-manifest format without loading private keys. */
public final class ConsortiumManifestGenerator {
    private static final int INPUT_VERSION = 1;
    private static final String ALGORITHM = "ECDSA_P256_SHA256";
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final DateTimeFormatter MILLIS =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private ConsortiumManifestGenerator() { }

    public static String signingRequests(String descriptorJson) {
        Descriptor descriptor = parseDescriptor(descriptorJson);
        JsonObject result = new JsonObject();
        result.addProperty("schemaVersion", INPUT_VERSION);
        result.addProperty("networkId", descriptor.networkId());
        JsonArray requests = new JsonArray();
        for (Request request : buildRequests(descriptor)) {
            JsonObject item = new JsonObject();
            item.addProperty("eventId", request.eventId());
            item.addProperty("eventType", request.eventType());
            item.addProperty("eventTime", MILLIS.format(request.eventTime()));
            item.add("data", stringMapJson(request.data()));
            item.addProperty("signingBytesBase64", Base64.getEncoder().encodeToString(request.signingBytes()));
            requests.add(item);
        }
        result.add("requests", requests);
        return JSON.toJson(result) + System.lineSeparator();
    }

    /** Builds an unsigned but fully verified manifest from public descriptor data and externally supplied signatures. */
    public static String assembleUnsignedManifest(String descriptorJson, String governanceSignaturesJson) {
        Descriptor descriptor = parseDescriptor(descriptorJson);
        List<Request> requests = buildRequests(descriptor);
        Map<String, String> signatures = parseSignatures(governanceSignaturesJson, requests);
        List<GovernanceTransaction> organizations = new ArrayList<>();
        List<GovernanceTransaction> peers = new ArrayList<>();
        for (Request request : requests) {
            String signature = signatures.get(request.eventId());
            try {
                if (!SignatureUtil.verifyP256Sha256(request.signingBytes(), Base64.getDecoder().decode(signature),
                        Base64.getDecoder().decode(descriptor.genesisAdminPublicKey()))) {
                    throw new IllegalArgumentException("Governance signature does not match event " + request.eventId());
                }
            } catch (GeneralSecurityException | IllegalArgumentException exception) {
                if (exception instanceof IllegalArgumentException illegal
                        && illegal.getMessage() != null && illegal.getMessage().startsWith("Governance signature")) {
                    throw illegal;
                }
                throw new IllegalArgumentException("Invalid governance signature for event " + request.eventId(), exception);
            }
            GovernanceType type = GovernanceType.valueOf(request.eventType());
            GovernanceTransaction unsigned = new GovernanceTransaction("0".repeat(64), request.eventId(), type,
                    request.eventTime(), request.data(), signature);
            GovernanceTransaction transaction = new GovernanceTransaction(
                    GovernanceCodec.transactionId(descriptor.networkId(), unsigned), request.eventId(), type,
                    request.eventTime(), request.data(), signature);
            (type == GovernanceType.REGISTER_ORGANIZATION ? organizations : peers).add(transaction);
        }
        organizations.sort(Comparator.comparing(GovernanceTransaction::transactionId));
        peers.sort(Comparator.comparing(GovernanceTransaction::transactionId));

        Block genesis = ProofOfWork.mine(descriptor.networkId(), 0, null, descriptor.genesisTimestamp(),
                descriptor.difficulty(), List.of(), java.math.BigInteger.ZERO);
        NetworkConfiguration network = new NetworkConfiguration(descriptor.networkId(), genesis.hash(),
                descriptor.difficulty(), descriptor.genesisTimestamp(), genesis.header().nonce(),
                descriptor.genesisAdminPublicKey());
        var verifiedGenesis = blockchain.GenesisBlockFactory.configuredGenesis(network);
        BlockValidator validator = new BlockValidator(descriptor.networkId(), descriptor.difficulty(),
                genesis.hash(), network.genesisAdminPublicKeyBytes());
        BlockValidationContext context = new BlockValidationContext(verifiedGenesis, Set.of(), Set.of(),
                GovernanceRegistry.empty(), Map.of(), Map.of(), Map.of());
        Block organizationBlock = ProofOfWork.mine(descriptor.networkId(), 1, genesis.hash(),
                descriptor.organizationBlockTimestamp(), descriptor.difficulty(),
                organizations.stream().map(GovernanceTransaction::transactionId).toList(), genesis.cumulativeWork());
        var organizationResult = validator.validate(organizationBlock, organizations, context);
        Block peerBlock = ProofOfWork.mine(descriptor.networkId(), 2, organizationBlock.hash(),
                descriptor.peerBlockTimestamp(), descriptor.difficulty(),
                peers.stream().map(GovernanceTransaction::transactionId).toList(), organizationBlock.cumulativeWork());
        validator.validate(peerBlock, peers, organizationResult.childContext());

        BootstrapManifest manifest = new BootstrapManifest(INPUT_VERSION, "development", descriptor.networkId(),
                descriptor.genesisAdminPublicKey(), descriptor.genesisTimestamp(), genesis.header().nonce(),
                descriptor.difficulty(), genesis.hash(), List.of(
                    manifestBlock(descriptor.organizationBlockTimestamp(), organizations, organizationBlock),
                    manifestBlock(descriptor.peerBlockTimestamp(), peers, peerBlock)), "");
        return manifestJson(manifest) + System.lineSeparator();
    }

    /** Attaches an external P1363 signature and validates the complete resulting manifest before writing it. */
    public static String finalizeManifest(String unsignedManifestJson, String signatureBase64) {
        BootstrapManifest manifest = BootstrapManifestCodec.read(unsignedManifestJson);
        if (manifest.signature() != null && !manifest.signature().isEmpty()) {
            throw new IllegalArgumentException("Input manifest must have an empty signature field");
        }
        String signature = signatureBase64 == null ? "" : signatureBase64.trim();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(signature); }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Manifest signature must be Base64 P-256 P1363 bytes", exception);
        }
        if (bytes.length != 64) throw new IllegalArgumentException("Manifest signature must contain 64 P1363 bytes");
        try {
            if (!SignatureUtil.verifyP256Sha256(BootstrapManifestCodec.signingBytes(manifest), bytes,
                    Base64.getDecoder().decode(manifest.genesisAdminPublicKey()))) {
                throw new IllegalArgumentException("Manifest signature does not match the genesis administrator key");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Could not verify manifest signature", exception);
        }
        JsonObject root = JsonParser.parseString(unsignedManifestJson).getAsJsonObject();
        root.addProperty("signature", signature);
        String completed = JSON.toJson(root) + System.lineSeparator();
        BootstrapManifestCodec.verify(BootstrapManifestCodec.read(completed));
        return completed;
    }

    private static BootstrapManifest.InitialBlock manifestBlock(Instant timestamp,
            List<GovernanceTransaction> transactions, Block block) {
        List<BootstrapManifest.InitialGovernance> entries = transactions.stream()
                .sorted(Comparator.comparing(GovernanceTransaction::transactionId))
                .map(tx -> new BootstrapManifest.InitialGovernance(tx.eventId(), tx.governanceType().name(),
                        tx.eventTime(), new TreeMap<>(tx.data()), tx.adminSignature())).toList();
        return new BootstrapManifest.InitialBlock(timestamp, entries, block.hash());
    }

    private static List<Request> buildRequests(Descriptor d) {
        List<Request> result = new ArrayList<>();
        for (Organization org : d.organizations().stream().sorted(Comparator.comparing(Organization::organizationId)).toList()) {
            Map<String, String> data = new TreeMap<>();
            data.put("organizationId", org.organizationId());
            data.put("organizationType", org.organizationType());
            data.put("name", org.name());
            data.put("keyId", org.keyId());
            data.put("algorithm", ALGORITHM);
            data.put("publicKey", org.publicKey());
            if (org.province() != null) data.put("province", org.province());
            result.add(request(d, "org:" + org.organizationId(), GovernanceType.REGISTER_ORGANIZATION, data));
        }
        for (Peer peer : d.peers().stream().sorted(Comparator.comparing(Peer::peerId)).toList()) {
            Map<String, String> data = new TreeMap<>();
            data.put("peerId", peer.peerId());
            data.put("organizationId", peer.organizationId());
            data.put("endpoint", peer.endpoint());
            data.put("tlsCertificateFingerprint", peer.tlsCertificateFingerprint());
            result.add(request(d, "peer:" + peer.peerId(), GovernanceType.REGISTER_PEER, data));
        }
        return List.copyOf(result);
    }

    private static Request request(Descriptor d, String purpose, GovernanceType type, Map<String, String> data) {
        String eventId = "bootstrap-local3:" + d.networkId() + ":" + purpose;
        if (eventId.length() > 255) throw new IllegalArgumentException("Generated governance event ID is too long");
        String placeholder = Base64.getEncoder().encodeToString(new byte[64]);
        GovernanceTransaction tx = new GovernanceTransaction("0".repeat(64), eventId, type,
                d.governanceEventTime(), data, placeholder);
        return new Request(eventId, type.name(), d.governanceEventTime(), data,
                GovernanceCodec.signingBytes(d.networkId(), tx));
    }

    private static Descriptor parseDescriptor(String json) {
        if (json == null || json.isBlank() || json.length() > 1_000_000)
            throw new IllegalArgumentException("Descriptor must contain 1 to 1,000,000 characters");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        requireKeys(root, Set.of("schemaVersion", "environment", "networkId", "genesisAdminPublicKey",
                "difficulty", "genesisTimestamp", "governanceEventTime", "organizationBlockTimestamp",
                "peerBlockTimestamp", "organizations", "peers"));
        if (root.get("schemaVersion").getAsInt() != INPUT_VERSION
                || !"development".equals(string(root, "environment")))
            throw new IllegalArgumentException("Only local development descriptors at schemaVersion 1 are supported");
        String networkId = string(root, "networkId");
        if (networkId.isBlank() || networkId.length() > 100) throw new IllegalArgumentException("Invalid networkId");
        String adminKey = string(root, "genesisAdminPublicKey");
        byte[] adminBytes = decodePublicKey(adminKey, "genesisAdminPublicKey");
        int difficulty = root.get("difficulty").getAsInt();
        if (difficulty < 1 || difficulty > 16) throw new IllegalArgumentException("difficulty must be between 1 and 16");
        Instant genesisTime = instant(root, "genesisTimestamp");
        Instant eventTime = instant(root, "governanceEventTime");
        Instant organizationTime = instant(root, "organizationBlockTimestamp");
        Instant peerTime = instant(root, "peerBlockTimestamp");
        if (!organizationTime.isAfter(genesisTime) || !peerTime.isAfter(organizationTime))
            throw new IllegalArgumentException("Block timestamps must strictly increase after genesis");
        if (!root.get("organizations").isJsonArray() || !root.get("peers").isJsonArray())
            throw new IllegalArgumentException("organizations and peers must be arrays");
        List<Organization> organizations = new ArrayList<>();
        Set<String> ids = new HashSet<>(), keyIds = new HashSet<>(), types = new HashSet<>();
        for (JsonElement e : root.getAsJsonArray("organizations")) {
            JsonObject o = e.getAsJsonObject();
            Set<String> allowed = Set.of("organizationId", "organizationType", "name", "keyId", "publicKey", "province");
            if (!allowed.containsAll(o.keySet()) || !o.keySet().containsAll(
                    Set.of("organizationId", "organizationType", "name", "keyId", "publicKey")))
                throw new IllegalArgumentException("Organization descriptor has missing or unexpected fields");
            Organization org = new Organization(string(o, "organizationId"), string(o, "organizationType"),
                    string(o, "name"), o.has("province") ? string(o, "province") : null,
                    string(o, "keyId"), string(o, "publicKey"));
            if (!Set.of("FARMER", "CARRIER", "RETAILER").contains(org.organizationType()))
                throw new IllegalArgumentException("Local 3-node organization types must be FARMER, CARRIER, and RETAILER");
            if (!ids.add(org.organizationId()) || !keyIds.add(org.keyId()) || !types.add(org.organizationType()))
                throw new IllegalArgumentException("Organization IDs, key IDs, and organization types must be unique");
            decodePublicKey(org.publicKey(), "organization publicKey");
            organizations.add(org);
        }
        if (organizations.size() != 3 || types.size() != 3)
            throw new IllegalArgumentException("Exactly one FARMER, one CARRIER, and one RETAILER are required");
        List<Peer> peers = new ArrayList<>();
        Set<String> peerIds = new HashSet<>(), peerOrganizations = new HashSet<>();
        for (JsonElement e : root.getAsJsonArray("peers")) {
            JsonObject p = e.getAsJsonObject();
            requireKeys(p, Set.of("peerId", "organizationId", "endpoint", "tlsCertificateFingerprint"));
            Peer peer = new Peer(string(p, "peerId"), string(p, "organizationId"), string(p, "endpoint"),
                    string(p, "tlsCertificateFingerprint"));
            if (!peerIds.add(peer.peerId()) || !peerOrganizations.add(peer.organizationId()))
                throw new IllegalArgumentException("Peer IDs and organization assignments must be unique");
            if (!peer.tlsCertificateFingerprint().matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Peer fingerprint must be lowercase SHA-256 hex");
            if (!ids.contains(peer.organizationId()))
                throw new IllegalArgumentException("Each peer must reference a listed organization");
            peers.add(peer);
        }
        if (peers.size() != 3 || !peerOrganizations.equals(ids))
            throw new IllegalArgumentException("Exactly one peer registration is required for each organization");
        return new Descriptor(networkId, adminKey, difficulty, genesisTime, eventTime,
                organizationTime, peerTime, List.copyOf(organizations), List.copyOf(peers));
    }

    private static Map<String, String> parseSignatures(String json, List<Request> expected) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("Governance signature file is required");
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        requireKeys(root, Set.of("schemaVersion", "signatures"));
        if (root.get("schemaVersion").getAsInt() != INPUT_VERSION || !root.get("signatures").isJsonArray())
            throw new IllegalArgumentException("Invalid governance signature file");
        Set<String> required = new HashSet<>(); expected.forEach(r -> required.add(r.eventId()));
        Map<String, String> values = new HashMap<>();
        for (JsonElement e : root.getAsJsonArray("signatures")) {
            JsonObject o = e.getAsJsonObject(); requireKeys(o, Set.of("eventId", "signature"));
            String id = string(o, "eventId"), signature = string(o, "signature");
            byte[] decoded;
            try { decoded = Base64.getDecoder().decode(signature); }
            catch (IllegalArgumentException ex) { throw new IllegalArgumentException("Governance signature is not Base64", ex); }
            if (decoded.length != 64 || !required.contains(id) || values.putIfAbsent(id, signature) != null)
                throw new IllegalArgumentException("Signature file has an invalid, unexpected, or duplicate signature");
        }
        if (!values.keySet().equals(required)) throw new IllegalArgumentException("A signature is required for every governance request");
        return values;
    }

    private static byte[] decodePublicKey(String value, String field) {
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            SignatureUtil.validateP256PublicKey(bytes);
            return bytes;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException(field + " must be a Base64 P-256 SPKI public key", exception);
        }
    }

    private static Instant instant(JsonObject object, String key) {
        String text = string(object, key);
        try {
            Instant value = Instant.parse(text);
            if (!MILLIS.format(value).equals(text)) throw new IllegalArgumentException("timestamp must use UTC and exactly 3 fractional digits");
            return value;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException(key + " must be a UTC timestamp with millisecond precision", exception);
        }
    }

    private static String string(JsonObject object, String key) {
        if (!object.has(key) || !object.get(key).isJsonPrimitive() || !object.getAsJsonPrimitive(key).isString())
            throw new IllegalArgumentException(key + " must be a string");
        return object.get(key).getAsString();
    }

    private static void requireKeys(JsonObject object, Set<String> keys) {
        if (!object.keySet().equals(keys)) throw new IllegalArgumentException("Input has missing or unexpected fields");
    }

    private static JsonObject stringMapJson(Map<String, String> values) {
        JsonObject result = new JsonObject();
        new TreeMap<>(values).forEach(result::addProperty);
        return result;
    }

    private static String manifestJson(BootstrapManifest manifest) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", manifest.schemaVersion());
        root.addProperty("environment", manifest.environment());
        root.addProperty("networkId", manifest.networkId());
        root.addProperty("genesisAdminPublicKey", manifest.genesisAdminPublicKey());
        root.addProperty("genesisTimestamp", MILLIS.format(manifest.genesisTimestamp()));
        root.addProperty("genesisNonce", manifest.genesisNonce());
        root.addProperty("difficulty", manifest.difficulty());
        root.addProperty("genesisHash", manifest.genesisHash());
        JsonArray blocks = new JsonArray();
        for (BootstrapManifest.InitialBlock block : manifest.initialBlocks()) {
            JsonObject b = new JsonObject(); b.addProperty("timestamp", MILLIS.format(block.timestamp()));
            JsonArray txs = new JsonArray();
            for (BootstrapManifest.InitialGovernance tx : block.transactions()) {
                JsonObject t = new JsonObject(); t.addProperty("eventId", tx.eventId());
                t.addProperty("eventType", tx.eventType()); t.addProperty("eventTime", MILLIS.format(tx.eventTime()));
                t.add("data", stringMapJson(tx.data())); t.addProperty("adminSignature", tx.adminSignature()); txs.add(t);
            }
            b.add("transactions", txs); b.addProperty("hash", block.hash()); blocks.add(b);
        }
        root.add("initialBlocks", blocks); root.addProperty("signature", manifest.signature());
        return JSON.toJson(root);
    }

    public static void main(String[] args) {
        try {
            if (args.length == 3 && "requests".equals(args[0])) {
                Files.writeString(Path.of(args[2]), signingRequests(Files.readString(Path.of(args[1]), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                return;
            }
            if (args.length == 4 && "assemble".equals(args[0])) {
                Files.writeString(Path.of(args[3]), assembleUnsignedManifest(
                        Files.readString(Path.of(args[1]), StandardCharsets.UTF_8),
                        Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                return;
            }
            if (args.length == 4 && "finalize".equals(args[0])) {
                Files.writeString(Path.of(args[3]), finalizeManifest(
                        Files.readString(Path.of(args[1]), StandardCharsets.UTF_8),
                        Files.readString(Path.of(args[2]), StandardCharsets.UTF_8)), StandardCharsets.UTF_8);
                return;
            }
            throw new IllegalArgumentException("Usage: requests <descriptor.json> <requests.json> | assemble <descriptor.json> <governance-signatures.json> <unsigned-manifest.json> | finalize <unsigned-manifest.json> <manifest-signature.txt> <signed-manifest.json>");
        } catch (IOException | RuntimeException exception) {
            System.err.println("Manifest generation failed: " + (exception.getMessage() == null ? "invalid input" : exception.getMessage()));
            System.exit(2);
        }
    }

    private record Descriptor(String networkId, String genesisAdminPublicKey, int difficulty,
            Instant genesisTimestamp, Instant governanceEventTime, Instant organizationBlockTimestamp,
            Instant peerBlockTimestamp, List<Organization> organizations, List<Peer> peers) { }
    private record Organization(String organizationId, String organizationType, String name,
            String province, String keyId, String publicKey) { }
    private record Peer(String peerId, String organizationId, String endpoint, String tlsCertificateFingerprint) { }
    private record Request(String eventId, String eventType, Instant eventTime,
            Map<String, String> data, byte[] signingBytes) { }
}