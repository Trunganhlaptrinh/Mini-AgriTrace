package bootstrap;

import blockchain.BlockCodec;
import blockchain.GovernanceCodec;
import blockchain.SignatureUtil;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import config.NetworkConfiguration;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import model.GovernanceTransaction;
import model.GovernanceType;

/** Strict parser and cryptographic verifier for offline bootstrap bundles. */
public final class BootstrapManifestCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls()
            .registerTypeAdapter(Instant.class, (JsonSerializer<Instant>) (value, type, context) ->
                    new com.google.gson.JsonPrimitive(value.toString()))
            .registerTypeAdapter(Instant.class, (JsonDeserializer<Instant>) (value, type, context) ->
                    Instant.parse(value.getAsString()))
            .create();
    private BootstrapManifestCodec() { }

    public static BootstrapManifest read(String json) {
        if (json == null || json.isBlank() || json.length() > 2_000_000) {
            throw new IllegalArgumentException("Manifest must contain 1 to 2,000,000 characters");
        }
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        requireKeys(root, "schemaVersion", "environment", "networkId", "genesisAdminPublicKey",
                "genesisTimestamp", "genesisNonce", "difficulty", "genesisHash", "initialBlocks", "signature");
        for (String key : root.keySet()) {
            if (root.get(key).isJsonNull()) throw new IllegalArgumentException("Manifest fields must not be null");
        }
        if (!root.get("initialBlocks").isJsonArray()) throw new IllegalArgumentException("initialBlocks must be an array");
        for (var blockElement : root.getAsJsonArray("initialBlocks")) {
            if (!blockElement.isJsonObject()) throw new IllegalArgumentException("Each initial block must be an object");
            JsonObject block = blockElement.getAsJsonObject();
            requireKeys(block, "timestamp", "transactions", "hash");
            if (block.get("timestamp").isJsonNull() || block.get("transactions").isJsonNull()
                    || block.get("hash").isJsonNull() || !block.get("transactions").isJsonArray())
                throw new IllegalArgumentException("Initial block fields are invalid");
            for (var txElement : block.getAsJsonArray("transactions")) {
                if (!txElement.isJsonObject()) throw new IllegalArgumentException("Each governance transaction must be an object");
                JsonObject tx = txElement.getAsJsonObject();
                requireKeys(tx, "eventId", "eventType", "eventTime", "data", "adminSignature");
                if (tx.entrySet().stream().anyMatch(e -> e.getValue().isJsonNull())
                        || !tx.get("data").isJsonObject())
                    throw new IllegalArgumentException("Governance transaction fields are invalid");
                for (var field : tx.getAsJsonObject("data").entrySet()) {
                    if (!field.getValue().isJsonPrimitive() || !field.getValue().getAsJsonPrimitive().isString())
                        throw new IllegalArgumentException("Governance data values must be strings");
                }
            }
        }
        BootstrapManifest manifest = GSON.fromJson(root, BootstrapManifest.class);
        if (!GSON.toJson(manifest).equals(GSON.toJson(GSON.fromJson(json, BootstrapManifest.class)))) {
            throw new IllegalArgumentException("Manifest fields are invalid");
        }
        return manifest;
    }

    public static byte[] signingBytes(BootstrapManifest m) {
        JsonObject object = JsonParser.parseString(GSON.toJson(m)).getAsJsonObject();
        object.remove("signature");
        return blockchain.CanonicalJson.canonicalize(object.toString()).getBytes(StandardCharsets.UTF_8);
    }

    public static String digest(BootstrapManifest m) {
        return blockchain.HashUtil.sha256Hex(signingBytes(m));
    }

    public static String toJson(BootstrapManifest manifest) {
        if (manifest == null) throw new IllegalArgumentException("manifest must not be null");
        return GSON.toJson(manifest);
    }

    public static Verified verify(BootstrapManifest m) {
        if (m == null || m.schemaVersion() != 1 || m.environment() == null
                || !List.of("development", "staging", "production").contains(m.environment())) {
            throw new IllegalArgumentException("Unsupported bootstrap manifest version or environment");
        }
        NetworkConfiguration network = new NetworkConfiguration(m.networkId(), m.genesisHash(),
                m.difficulty(), m.genesisTimestamp(), m.genesisNonce(), m.genesisAdminPublicKey());
        try {
            if (!SignatureUtil.verifyP256Sha256(signingBytes(m), Base64.getDecoder().decode(m.signature()),
                    network.genesisAdminPublicKeyBytes())) {
                throw new IllegalArgumentException("Manifest signature is invalid");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Manifest signature or public key is invalid", e);
        }
        if (m.initialBlocks().size() > 1000) throw new IllegalArgumentException("Too many initial blocks");
        List<List<GovernanceTransaction>> blocks = new ArrayList<>();
        List<model.Block> expectedBlocks = new ArrayList<>();
        String previous = m.genesisHash();
        var parent = blockchain.GenesisBlockFactory.configuredGenesis(network);
        var validator = new blockchain.BlockValidator(m.networkId(), m.difficulty(), m.genesisHash(),
                network.genesisAdminPublicKeyBytes());
        var context = blockchain.BlockValidationContext.genesis(blockchain.GovernanceRegistry.empty());
        for (int i = 0; i < m.initialBlocks().size(); i++) {
            var spec = m.initialBlocks().get(i);
            if (spec.timestamp() == null || spec.timestamp().isBefore(parent.header().timestamp()))
                throw new IllegalArgumentException("Initial block timestamp is invalid");
            List<GovernanceTransaction> txs = new ArrayList<>();
            for (var tx : spec.transactions()) {
                GovernanceType type;
                try { type = GovernanceType.valueOf(tx.eventType()); }
                catch (RuntimeException e) { throw new IllegalArgumentException("Unknown governance event type", e); }
                var candidate = new GovernanceTransaction("0".repeat(64), tx.eventId(), type,
                        tx.eventTime(), tx.data(), tx.adminSignature());
                txs.add(new GovernanceTransaction(GovernanceCodec.transactionId(m.networkId(), candidate),
                        tx.eventId(), type, tx.eventTime(), tx.data(), tx.adminSignature()));
            }
            txs.sort(Comparator.comparing(GovernanceTransaction::transactionId));
            var block = blockchain.ProofOfWork.mine(m.networkId(), i + 1L, previous,
                    spec.timestamp(), m.difficulty(), txs.stream().map(GovernanceTransaction::transactionId).toList(),
                    parent.cumulativeWork());
            if (!block.hash().equals(spec.hash())) throw new IllegalArgumentException("Initial block hash mismatch");
            var result = validator.validate(block, txs, context);
            context = result.childContext(); parent = block; previous = block.hash(); blocks.add(List.copyOf(txs));
            expectedBlocks.add(block);
        }
        return new Verified(network, blockchain.GenesisBlockFactory.configuredGenesis(network),
                List.copyOf(blocks), previous, context.governanceRegistry(), List.copyOf(expectedBlocks));
    }

    private static void requireKeys(JsonObject o, String... names) {
        if (!o.keySet().equals(java.util.Set.of(names))) throw new IllegalArgumentException("Manifest has missing or unexpected fields");
    }
    public record Verified(NetworkConfiguration network, model.Block genesis,
                           List<List<GovernanceTransaction>> transactionsByBlock, String tipHash,
                           blockchain.GovernanceRegistry registry, List<model.Block> expectedBlocks) { }
}
