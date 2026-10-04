package network;

import blockchain.BlockRepository;
import blockchain.BlockCodec;
import blockchain.ProofOfWork;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigInteger;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import model.Block;
import model.BlockHeader;
import model.LedgerTransaction;

public final class LedgerBlockWireCodec {
    private static final Set<String> FIELDS = Set.of(
            "networkId", "height", "previousHash", "timestamp", "nonce",
            "difficulty", "transactionsHash", "cumulativeWork", "hash", "transactions");

    private LedgerBlockWireCodec() {
    }

    public static JsonObject encode(String networkId, BlockRepository.StoredBlock storedBlock) {
        if (storedBlock == null) {
            throw new IllegalArgumentException("storedBlock must not be null");
        }
        Block block = storedBlock.block();
        if (!block.header().networkId().equals(networkId)
                || !block.hash().equals(BlockCodec.hashHeader(block.header()))
                || !block.header().transactionsHash().equals(
                        BlockCodec.transactionsHash(block.transactionIds()))
                || !ProofOfWork.hasValidProof(block)) {
            throw new IllegalArgumentException("Stored block failed wire-format integrity checks");
        }
        JsonObject wire = new JsonObject();
        wire.addProperty("networkId", block.header().networkId());
        wire.addProperty("height", block.header().height());
        if (block.header().previousHash() == null) {
            wire.add("previousHash", com.google.gson.JsonNull.INSTANCE);
        } else {
            wire.addProperty("previousHash", block.header().previousHash());
        }
        wire.addProperty("timestamp", block.header().timestamp().toString());
        wire.addProperty("nonce", block.header().nonce());
        wire.addProperty("difficulty", block.header().difficulty());
        wire.addProperty("transactionsHash", block.header().transactionsHash());
        wire.addProperty("cumulativeWork", block.cumulativeWork().toString());
        wire.addProperty("hash", block.hash());
        JsonArray transactions = new JsonArray();
        for (LedgerTransaction transaction : storedBlock.transactions()) {
            transactions.add(LedgerTransactionWireCodec.encode(networkId, transaction));
        }
        wire.add("transactions", transactions);
        return wire;
    }

    public static BlockRepository.StoredBlock decode(String networkId, String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (com.google.gson.JsonParseException exception) {
            throw new IllegalArgumentException("Block JSON is malformed", exception);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new IllegalArgumentException("Block must be a JSON object");
        }
        JsonObject wire = parsed.getAsJsonObject();
        if (!FIELDS.equals(wire.keySet())) {
            throw new IllegalArgumentException("Block contains missing or unsupported fields");
        }
        String blockNetworkId = requiredString(wire, "networkId");
        if (!networkId.equals(blockNetworkId)) {
            throw new IllegalArgumentException("Block belongs to a different network");
        }
        JsonElement previousHashElement = wire.get("previousHash");
        String previousHash;
        if (previousHashElement == null || previousHashElement.isJsonNull()) {
            previousHash = null;
        } else if (previousHashElement.isJsonPrimitive()
                && previousHashElement.getAsJsonPrimitive().isString()) {
            previousHash = previousHashElement.getAsString();
        } else {
            throw new IllegalArgumentException("previousHash must be a string or null");
        }
        long height = requiredLong(wire, "height");
        long nonce = requiredLong(wire, "nonce");
        int difficulty = Math.toIntExact(requiredLong(wire, "difficulty"));
        Instant timestamp;
        BigInteger cumulativeWork;
        try {
            timestamp = Instant.parse(requiredString(wire, "timestamp"));
            String workText = requiredString(wire, "cumulativeWork");
            if (!workText.matches("0|[1-9][0-9]*")) {
                throw new NumberFormatException("cumulativeWork is not canonical decimal");
            }
            cumulativeWork = new BigInteger(workText);
        } catch (DateTimeParseException | NumberFormatException exception) {
            throw new IllegalArgumentException("Block timestamp or cumulativeWork is invalid", exception);
        }
        BlockHeader header = new BlockHeader(
                blockNetworkId,
                height,
                previousHash,
                timestamp,
                nonce,
                difficulty,
                requiredString(wire, "transactionsHash"));
        JsonElement transactionsElement = wire.get("transactions");
        if (transactionsElement == null || !transactionsElement.isJsonArray()) {
            throw new IllegalArgumentException("Block transactions must be an array");
        }
        List<LedgerTransaction> transactions = new ArrayList<>();
        for (JsonElement element : transactionsElement.getAsJsonArray()) {
            transactions.add(LedgerTransactionWireCodec.decode(networkId, element));
        }
        List<String> transactionIds = transactions.stream()
                .map(LedgerTransaction::transactionId)
                .sorted()
                .toList();
        if (!transactions.stream().map(LedgerTransaction::transactionId).toList().equals(transactionIds)) {
            throw new IllegalArgumentException("Block transactions must be ordered by transaction ID");
        }
        Block block = new Block(
                header,
                transactionIds,
                cumulativeWork,
                requiredString(wire, "hash"));
        return new BlockRepository.StoredBlock(block, transactions);
    }

    private static String requiredString(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()
                || element.getAsString().isBlank()) {
            throw new IllegalArgumentException(field + " must be a non-blank string");
        }
        return element.getAsString();
    }

    private static long requiredLong(JsonObject object, String field) {
        JsonElement element = object.get(field);
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(field + " must be an integer");
        }
        try {
            return element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(field + " must be an integer in range", exception);
        }
    }
}
