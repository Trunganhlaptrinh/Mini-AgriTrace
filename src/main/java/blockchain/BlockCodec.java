package blockchain;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import model.BlockHeader;

public final class BlockCodec {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            new DateTimeFormatterBuilder().appendInstant(3).toFormatter();

    private BlockCodec() {
    }

    public static String transactionsHash(List<String> transactionIds) {
        if (transactionIds == null) {
            throw new IllegalArgumentException("transactionIds must not be null");
        }
        List<String> sorted = new ArrayList<>(transactionIds);
        sorted.sort(String::compareTo);
        if (sorted.stream().anyMatch(id -> id == null || !id.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException("transaction IDs must be lowercase SHA-256 hex digests");
        }
        if (sorted.stream().distinct().count() != sorted.size()) {
            throw new IllegalArgumentException("transaction IDs must not contain duplicates");
        }
        String canonical = CanonicalJson.canonicalize(GSON.toJson(sorted));
        return HashUtil.sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    }

    public static String canonicalHeaderJson(BlockHeader header) {
        if (header == null) {
            throw new IllegalArgumentException("header must not be null");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("networkId", header.networkId());
        fields.put("height", header.height());
        fields.put("previousHash", header.previousHash());
        fields.put("timestamp", TIMESTAMP_FORMATTER.format(header.timestamp()));
        fields.put("nonce", header.nonce());
        fields.put("difficulty", header.difficulty());
        fields.put("transactionsHash", header.transactionsHash());
        return CanonicalJson.canonicalize(GSON.toJson(fields));
    }

    public static String hashHeader(BlockHeader header) {
        return HashUtil.sha256Hex(canonicalHeaderJson(header).getBytes(StandardCharsets.UTF_8));
    }
}
