package service;

import blockchain.BlockRepository;
import blockchain.Blockchain;
import blockchain.ChainSnapshot;
import blockchain.GovernanceRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.EventType;
import model.GovernedOrganization;
import model.LedgerTransaction;
import model.SignatureEnvelope;

public final class TraceabilityService {
    private static final Set<String> HARVEST_PUBLIC_FIELDS = Set.of(
            "productType", "variety", "harvestDate", "quantity", "quantityUnit", "farmName", "province");
    private static final Set<String> SHIPMENT_PUBLIC_FIELDS = Set.of("fromProvince", "toProvince");

    private final Blockchain blockchain;
    private final Clock clock;

    public TraceabilityService(Blockchain blockchain) {
        this(blockchain, Clock.systemUTC());
    }

    TraceabilityService(Blockchain blockchain, Clock clock) {
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Optional<BatchView> findBatch(String batchCode, AuthenticatedAccount actor) {
        requireBatchCode(batchCode);
        if (actor == null) {
            throw new AuthenticationException(
                    "UNAUTHENTICATED", "Authentication is required", 401);
        }
        ChainData chain = loadChainData();
        BatchSnapshot snapshot = chain.snapshot().nextBlockContext().batches().get(batchCode);
        if (snapshot == null) {
            return Optional.empty();
        }
        List<EventView> events = eventsForBatch(batchCode, chain);
        if (!canAccess(actor, snapshot, events)) {
            throw new AuthenticationException(
                    "FORBIDDEN", "Account is not a participant in this batch", 403);
        }
        BatchEvent harvest = events.stream()
                .map(event -> chain.eventsById().get(event.transactionId()))
                .filter(Objects::nonNull)
                .filter(event -> event.eventType() == EventType.HARVESTED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Validated batch has no canonical HARVESTED event"));
        return Optional.of(new BatchView(
                batchCode,
                snapshot.state().name(),
                snapshot.farmerOrganizationId(),
                snapshot.currentHolderOrganizationId(),
                publicData(harvest),
                events,
                chainHeight(chain.snapshot().tip())));
    }

    public Optional<PublicTrace> findPublicTrace(String batchCode) {
        requireBatchCode(batchCode);
        ChainData chain = loadChainData();
        BatchSnapshot snapshot = chain.snapshot().nextBlockContext().batches().get(batchCode);
        if (snapshot == null) {
            return Optional.empty();
        }
        List<EventView> events = eventsForBatch(batchCode, chain);
        BatchEvent harvest = events.stream()
                .map(event -> chain.eventsById().get(event.transactionId()))
                .filter(Objects::nonNull)
                .filter(event -> event.eventType() == EventType.HARVESTED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Validated batch has no canonical HARVESTED event"));
        return Optional.of(new PublicTrace(
                batchCode,
                publicData(harvest),
                snapshot.state().name(),
                organizationDisplay(chain.registry(), snapshot.currentHolderOrganizationId()),
                events,
                new Verification(true, chainHeight(chain.snapshot().tip()), clock.instant())));
    }

    private ChainData loadChainData() {
        Blockchain.ValidatedCanonicalChain validated =
                blockchain.loadValidatedCanonicalChainSnapshot();
        List<BlockRepository.StoredBlock> chain = validated.blocks();
        ChainSnapshot snapshot = validated.snapshot();
        Map<String, BatchEvent> eventsById = new LinkedHashMap<>();
        Map<String, Block> blocksByTransactionId = new LinkedHashMap<>();
        for (BlockRepository.StoredBlock stored : chain) {
            for (LedgerTransaction transaction : stored.transactions()) {
                if (transaction instanceof BatchEvent event) {
                    eventsById.put(event.transactionId(), event);
                    blocksByTransactionId.put(event.transactionId(), stored.block());
                }
            }
        }
        return new ChainData(
                snapshot,
                snapshot.nextBlockContext().governanceRegistry(),
                eventsById,
                blocksByTransactionId);
    }

    private List<EventView> eventsForBatch(String batchCode, ChainData chain) {
        List<EventView> events = new ArrayList<>();
        for (Map.Entry<String, BatchEvent> entry : chain.eventsById().entrySet()) {
            BatchEvent event = entry.getValue();
            if (!batchCode.equals(event.batchCode())) {
                continue;
            }
            Block block = chain.blocksByTransactionId().get(event.transactionId());
            if (block == null) {
                throw new IllegalStateException("Canonical batch event has no containing block");
            }
            List<SignerView> signers = event.signatures().stream()
                    .map(signature -> signerView(chain.registry(), signature))
                    .toList();
            events.add(new EventView(
                    event.transactionId(),
                    event.eventType().name(),
                    event.eventTime(),
                    actorOrganizationId(event),
                    signers,
                    publicData(event),
                    block.header().height(),
                    block.hash(),
                    block.header().timestamp()));
        }
        events.sort((left, right) -> {
            int byHeight = Long.compare(left.blockHeight(), right.blockHeight());
            if (byHeight != 0) {
                return byHeight;
            }
            return left.transactionId().compareTo(right.transactionId());
        });
        return List.copyOf(events);
    }

    static boolean canAccess(
            AuthenticatedAccount actor,
            BatchSnapshot snapshot,
            List<EventView> events
    ) {
        if ("ADMIN".equals(actor.role())) {
            return true;
        }
        String organizationId = actor.organizationId();
        if (organizationId == null) {
            return false;
        }
        return organizationId.equals(snapshot.farmerOrganizationId())
                || organizationId.equals(snapshot.currentHolderOrganizationId())
                || organizationId.equals(snapshot.expectedCarrierOrganizationId())
                || organizationId.equals(snapshot.expectedRecipientOrganizationId())
                || events.stream().anyMatch(event ->
                organizationId.equals(event.actorOrganizationId())
                        || event.signers().stream().anyMatch(signer ->
                                organizationId.equals(signer.organizationId())));
    }

    private Map<String, Object> publicData(BatchEvent event) {
        Map<String, Object> result = new LinkedHashMap<>();
        Set<String> allowedFields = switch (event.eventType()) {
            case HARVESTED -> HARVEST_PUBLIC_FIELDS;
            case SHIPPED -> SHIPMENT_PUBLIC_FIELDS;
            case CORRECTION -> Set.of("correctionOfTxId", "correctedPublicData");
            case PACKAGED, RECEIVED, SOLD -> Set.of();
        };
        for (String key : allowedFields) {
            Object value = event.data().get(key);
            if (value == null) {
                continue;
            }
            if (key.equals("correctedPublicData") && value instanceof Map<?, ?> corrected) {
                Map<String, Object> publicCorrection = new LinkedHashMap<>();
                for (String publicField : HARVEST_PUBLIC_FIELDS) {
                    Object correctedValue = corrected.get(publicField);
                    if (correctedValue != null) {
                        publicCorrection.put(publicField, correctedValue);
                    }
                }
                if (!publicCorrection.isEmpty()) {
                    result.put(key, Map.copyOf(publicCorrection));
                }
            } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
                result.put(key, value);
            }
        }
        return Map.copyOf(result);
    }

    private String actorOrganizationId(BatchEvent event) {
        if (event.eventType() == EventType.SHIPPED) {
            Object sender = event.data().get("senderOrganizationId");
            return sender instanceof String value ? value : "";
        }
        return event.signatures().isEmpty() ? "" : event.signatures().get(0).organizationId();
    }

    private SignerView signerView(GovernanceRegistry registry, SignatureEnvelope signature) {
        GovernedOrganization organization = registry.organizations().get(signature.organizationId());
        return new SignerView(
                signature.organizationId(),
                organization == null ? "Unknown organization" : organization.name(),
                organization == null ? null : organization.type().name());
    }

    private String organizationDisplay(GovernanceRegistry registry, String organizationId) {
        if (organizationId == null) {
            return null;
        }
        GovernedOrganization organization = registry.organizations().get(organizationId);
        return organization == null ? null : organization.name();
    }

    private long chainHeight(Block tip) {
        return tip == null ? 0 : tip.header().height();
    }

    private void requireBatchCode(String batchCode) {
        if (batchCode == null || batchCode.isBlank() || batchCode.length() > 100) {
            throw new IllegalArgumentException("batchCode must contain 1 to 100 characters");
        }
    }

    private record ChainData(
            ChainSnapshot snapshot,
            GovernanceRegistry registry,
            Map<String, BatchEvent> eventsById,
            Map<String, Block> blocksByTransactionId
    ) {
    }

    public record BatchView(
            String batchCode,
            String currentStatus,
            String farmerOrganizationId,
            String currentHolderOrganizationId,
            Map<String, Object> publicFields,
            List<EventView> events,
            long chainHeight
    ) {
        public BatchView {
            publicFields = Map.copyOf(publicFields);
            events = List.copyOf(events);
        }
    }

    public record PublicTrace(
            String batchCode,
            Map<String, Object> publicFields,
            String currentStatus,
            String currentHolderName,
            List<EventView> events,
            Verification verification
    ) {
        public PublicTrace {
            publicFields = Map.copyOf(publicFields);
            events = List.copyOf(events);
            Objects.requireNonNull(verification, "verification");
        }
    }

    public record EventView(
            String transactionId,
            String eventType,
            Instant eventTime,
            String actorOrganizationId,
            List<SignerView> signers,
            Map<String, Object> publicData,
            long blockHeight,
            String blockHash,
            Instant blockTimestamp
    ) {
        public EventView {
            signers = List.copyOf(signers);
            publicData = Map.copyOf(publicData);
        }
    }

    public record SignerView(
            String organizationId,
            String displayName,
            String role
    ) {
    }

    public record Verification(boolean valid, long chainHeight, Instant checkedAt) {
    }
}
