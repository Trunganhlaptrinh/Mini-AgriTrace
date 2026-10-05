package network;

import blockchain.BlockRepository;
import blockchain.Blockchain;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import model.GovernedOrganization;
import model.LedgerTransaction;
import model.OrganizationStatus;
import model.PeerRegistration;
import service.PeerLedgerService;

public final class PeerLedgerSynchronizer {
    private static final Logger LOGGER = Logger.getLogger(PeerLedgerSynchronizer.class.getName());
    private static final int MAX_RESPONSE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_BLOCKS_PER_PEER_CYCLE = 128;
    private static final String P2P_PREFIX = "/api/v1/internal/p2p";

    private final Blockchain blockchain;
    private final PeerLedgerService peerLedgerService;
    private final PeerIdentity identity;
    private final java.net.http.HttpClient httpClient;

    public PeerLedgerSynchronizer(
            Blockchain blockchain,
            PeerLedgerService peerLedgerService,
            PeerIdentity identity
    ) {
        this.blockchain = java.util.Objects.requireNonNull(blockchain, "blockchain");
        this.peerLedgerService = java.util.Objects.requireNonNull(peerLedgerService, "peerLedgerService");
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.httpClient = identity.httpClient();
    }

    public void synchronizeWithActivePeers() {
        List<PeerRegistration> peers = activePeers();
        for (PeerRegistration peer : peers) {
            try {
                synchronizePeer(peer);
            } catch (PeerDeliveryException | IllegalArgumentException
                    | blockchain.BlockValidationException
                    | blockchain.TransactionValidationException exception) {
                LOGGER.warning("Ledger synchronization with peer " + peer.peerId()
                        + " failed: " + exception.getMessage());
            } catch (RuntimeException exception) {
                LOGGER.warning("Ledger synchronization with peer " + peer.peerId()
                        + " failed: " + exception.getClass().getSimpleName());
            }
        }
    }

    public void synchronizePeer(PeerRegistration peer) {
        if (peer == null || !peer.active()
                || peer.peerId().equals(identity.registration().peerId())) {
            throw new IllegalArgumentException("An active remote peer is required");
        }
        synchronizeCanonicalBlocks(peer);
        synchronizePendingTransactions(peer);
    }

    private List<PeerRegistration> activePeers() {
        var registry = blockchain.loadValidatedCanonicalChainSnapshot()
                .snapshot().nextBlockContext().governanceRegistry();
        return registry.peers().values().stream()
                .filter(PeerRegistration::active)
                .filter(peer -> !peer.peerId().equals(identity.registration().peerId()))
                .filter(peer -> {
                    GovernedOrganization organization =
                            registry.organizations().get(peer.organizationId());
                    return organization != null && organization.status() == OrganizationStatus.ACTIVE;
                })
                .toList();
    }

    private void synchronizeCanonicalBlocks(PeerRegistration peer) {
        List<LocatorEntry> remoteLocator = locator(peer);
        List<BlockRepository.StoredBlock> localBlocks = peerLedgerService.canonicalBlocks();
        Map<String, Long> localHeights = new HashMap<>();
        localBlocks.forEach(stored -> localHeights.put(
                stored.block().hash(), stored.block().header().height()));
        LocatorEntry common = remoteLocator.stream()
                .filter(entry -> entry.height() == localHeights.getOrDefault(entry.hash(), -1L))
                .max(java.util.Comparator.comparingLong(entry -> entry.height()))
                .orElseThrow(() -> new PeerDeliveryException(
                        "Remote peer does not share a known canonical ancestor"));
        LocatorEntry remoteTip = remoteLocator.stream()
                .max(java.util.Comparator.comparingLong(LocatorEntry::height))
                .orElseThrow(() -> new PeerDeliveryException("Remote peer returned an empty chain locator"));
        if (common.height() > remoteTip.height()) {
            throw new PeerDeliveryException("Remote chain locator has inconsistent heights");
        }

        String afterHash = common.hash();
        long expectedHeight = common.height() + 1;
        int imported = 0;
        while (expectedHeight <= remoteTip.height()
                && imported < MAX_BLOCKS_PER_PEER_CYCLE) {
            JsonObject data = dataObject(get(
                    peer, P2P_PREFIX + "/blocks/next?afterHash=" + afterHash));
            BlockRepository.StoredBlock nextBlock = LedgerBlockWireCodec.decode(
                    peerLedgerService.networkId(), data.toString());
            if (nextBlock.block().header().height() != expectedHeight
                    || !afterHash.equals(nextBlock.block().header().previousHash())) {
                throw new PeerDeliveryException("Remote peer returned a non-contiguous canonical block");
            }
            peerLedgerService.receiveBlock(
                    peer, identity.registration(), nextBlock);
            afterHash = nextBlock.block().hash();
            expectedHeight++;
            imported++;
        }
        if (expectedHeight <= remoteTip.height()) {
            LOGGER.info("Ledger synchronization with peer " + peer.peerId()
                    + " imported " + imported + " blocks; remaining blocks will be pulled next cycle");
        }
    }

    private List<LocatorEntry> locator(PeerRegistration peer) {
        JsonObject data = dataObject(get(peer, P2P_PREFIX + "/chain/locator"));
        JsonElement blocksElement = data.get("blocks");
        if (blocksElement == null || !blocksElement.isJsonArray()) {
            throw new PeerDeliveryException("Remote peer returned an invalid chain locator");
        }
        JsonArray blocks = blocksElement.getAsJsonArray();
        List<LocatorEntry> entries = new java.util.ArrayList<>(blocks.size());
        long lastHeight = -1;
        for (JsonElement element : blocks) {
            if (!element.isJsonObject()) {
                throw new PeerDeliveryException("Remote peer returned an invalid chain locator entry");
            }
            JsonObject item = element.getAsJsonObject();
            if (item.size() != 2 || !item.has("height") || !item.has("hash")) {
                throw new PeerDeliveryException("Remote peer returned an invalid chain locator entry");
            }
            long height = integer(item.get("height"), "locator height");
            String hash = string(item.get("hash"), "locator hash");
            if (height <= lastHeight || !hash.matches("[0-9a-f]{64}")) {
                throw new PeerDeliveryException("Remote peer returned an unordered chain locator");
            }
            entries.add(new LocatorEntry(height, hash));
            lastHeight = height;
        }
        return List.copyOf(entries);
    }

    private void synchronizePendingTransactions(PeerRegistration peer) {
        String after = null;
        while (true) {
            String path = P2P_PREFIX + "/transactions/pending"
                    + (after == null ? "" : "?after=" + after);
            JsonObject data = dataObject(get(peer, path));
            JsonElement transactionsElement = data.get("transactions");
            JsonElement nextAfterElement = data.get("nextAfter");
            if (transactionsElement == null || !transactionsElement.isJsonArray()
                    || nextAfterElement == null || !nextAfterElement.isJsonPrimitive()
                    || !nextAfterElement.getAsJsonPrimitive().isString()) {
                throw new PeerDeliveryException("Remote peer returned an invalid pending transaction page");
            }
            for (JsonElement element : transactionsElement.getAsJsonArray()) {
                try {
                    LedgerTransaction transaction = LedgerTransactionWireCodec.decode(
                            peerLedgerService.networkId(), element);
                    peerLedgerService.receiveTransaction(
                            peer, identity.registration(), transaction);
                } catch (blockchain.TransactionValidationException exception) {
                    LOGGER.warning("Peer " + peer.peerId()
                            + " supplied a pending transaction rejected with " + exception.getCode());
                } catch (dal.DuplicateTransactionException exception) {
                    LOGGER.warning("Peer " + peer.peerId()
                            + " supplied a conflicting pending transaction: " + exception.getCode());
                } catch (IllegalArgumentException exception) {
                    LOGGER.warning("Peer " + peer.peerId()
                            + " supplied a malformed pending transaction; skipping the entry");
                }
            }
            String nextAfter = nextAfterElement.getAsString();
            if (nextAfter.isEmpty()) {
                return;
            }
            if (!nextAfter.matches("[0-9a-f]{64}") || nextAfter.equals(after)) {
                throw new PeerDeliveryException("Remote peer returned a non-advancing transaction cursor");
            }
            after = nextAfter;
        }
    }

    private String get(PeerRegistration peer, String path) {
        try {
            URI uri = endpointUri(peer.endpoint(), path);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream stream = response.body()) {
                byte[] bytes = stream.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
                    throw new PeerDeliveryException("Peer response exceeds the size limit");
                }
                String body = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (response.statusCode() != 200) {
                    throw new PeerDeliveryException(
                            "Peer " + peer.peerId() + " returned HTTP " + response.statusCode());
                }
                return body;
            }
        } catch (IOException exception) {
            throw new PeerDeliveryException(
                    "Could not read ledger data from peer " + peer.peerId() + ": "
                            + exception.getClass().getSimpleName());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PeerDeliveryException("Ledger synchronization was interrupted");
        }
    }

    private URI endpointUri(String endpoint, String path) {
        URI base;
        try {
            base = URI.create(endpoint);
        } catch (IllegalArgumentException exception) {
            throw new PeerDeliveryException("Registered peer endpoint is not a valid HTTPS endpoint");
        }
        if (!"https".equalsIgnoreCase(base.getScheme()) || base.getHost() == null
                || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null) {
            throw new PeerDeliveryException("Registered peer endpoint is not a valid HTTPS endpoint");
        }
        int queryIndex = path.indexOf('?');
        String requestPath = queryIndex < 0 ? path : path.substring(0, queryIndex);
        String query = queryIndex < 0 ? null : path.substring(queryIndex + 1);
        String normalizedPath = (base.getRawPath() == null ? "" : base.getRawPath())
                .replaceAll("/+$", "") + requestPath;
        String target = base.getScheme() + "://" + base.getRawAuthority() + normalizedPath
                + (query == null ? "" : "?" + query);
        try {
            return URI.create(target);
        } catch (IllegalArgumentException exception) {
            throw new PeerDeliveryException("Registered peer endpoint cannot be resolved");
        }
    }

    private JsonObject dataObject(String body) {
        try {
            JsonElement parsed = JsonParser.parseString(body);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("success")
                    || !parsed.getAsJsonObject().get("success").getAsBoolean()
                    || !parsed.getAsJsonObject().has("data")
                    || !parsed.getAsJsonObject().get("data").isJsonObject()) {
                throw new PeerDeliveryException("Peer returned an invalid response envelope");
            }
            return parsed.getAsJsonObject().getAsJsonObject("data");
        } catch (com.google.gson.JsonParseException | IllegalStateException exception) {
            throw new PeerDeliveryException("Peer returned malformed JSON");
        }
    }

    private long integer(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isNumber()) {
            throw new PeerDeliveryException("Peer returned an invalid " + field);
        }
        try {
            return element.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException exception) {
            throw new PeerDeliveryException("Peer returned an invalid " + field);
        }
    }

    private String string(JsonElement element, String field) {
        if (element == null || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new PeerDeliveryException("Peer returned an invalid " + field);
        }
        return element.getAsString();
    }

    private record LocatorEntry(long height, String hash) {
    }

}
