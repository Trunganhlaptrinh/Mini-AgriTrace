package service;

import blockchain.Blockchain;
import blockchain.BlockValidationContext;
import blockchain.SignatureUtil;
import blockchain.TransactionCodec;
import dal.DuplicateTransactionException;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import model.BatchEvent;
import model.BatchSnapshot;
import model.BatchState;
import model.EventType;
import model.GovernedOrganization;
import model.OrganizationKey;
import model.OrganizationStatus;
import model.OrganizationType;
import model.PeerRegistration;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import network.ShipmentEndorsementRelayer;
import network.ShipmentProposalRelayer;

public final class ShipmentProposalService {
    private final String networkId;
    private final Blockchain blockchain;
    private final ShipmentProposalRepository repository;
    private final ShipmentTransactionSubmitter localTransactionSubmitter;
    private final ShipmentTransactionSubmitter peerTransactionSubmitter;
    private final ShipmentProposalRelayer proposalRelayer;
    private final ShipmentEndorsementRelayer endorsementRelayer;
    private final Clock clock;

    public ShipmentProposalService(
            String networkId,
            Blockchain blockchain,
            ShipmentProposalRepository repository,
            TransactionService transactionService,
            ShipmentProposalRelayer proposalRelayer,
            ShipmentEndorsementRelayer endorsementRelayer
    ) {
        this(networkId, blockchain, repository, transactionService::submitAndProduce,
                transactionService::submit, proposalRelayer, endorsementRelayer, Clock.systemUTC());
    }

    ShipmentProposalService(
            String networkId,
            Blockchain blockchain,
            ShipmentProposalRepository repository,
            ShipmentTransactionSubmitter transactionSubmitter,
            ShipmentProposalRelayer proposalRelayer,
            ShipmentEndorsementRelayer endorsementRelayer,
            Clock clock
    ) {
        this(networkId, blockchain, repository, transactionSubmitter, transactionSubmitter,
                proposalRelayer, endorsementRelayer, clock);
    }

    ShipmentProposalService(
            String networkId,
            Blockchain blockchain,
            ShipmentProposalRepository repository,
            ShipmentTransactionSubmitter localTransactionSubmitter,
            ShipmentTransactionSubmitter peerTransactionSubmitter,
            ShipmentProposalRelayer proposalRelayer,
            ShipmentEndorsementRelayer endorsementRelayer,
            Clock clock
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        this.networkId = networkId;
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.localTransactionSubmitter = Objects.requireNonNull(
                localTransactionSubmitter, "localTransactionSubmitter");
        this.peerTransactionSubmitter = Objects.requireNonNull(
                peerTransactionSubmitter, "peerTransactionSubmitter");
        this.proposalRelayer = Objects.requireNonNull(proposalRelayer, "proposalRelayer");
        this.endorsementRelayer = Objects.requireNonNull(endorsementRelayer, "endorsementRelayer");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public ShipmentProposal create(AuthenticatedAccount actor, ShipmentProposal proposal) {
        requireOrganizationAccount(actor);
        if (proposal == null || proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER) {
            throw new IllegalArgumentException("new proposal must be awaiting carrier endorsement");
        }
        validateEnvelope(proposal);
        if (!actor.organizationId().equals(field(proposal.event(), "senderOrganizationId"))) {
            throw forbidden("Only the shipment sender may create its proposal");
        }
        validateProposalAgainstCanonicalState(actor, proposal);
        insertOrMatch(proposal);
        proposalRelayer.relay(proposal);
        return proposal;
    }

    public ReceiveResult receiveFromPeer(
            PeerRegistration sourcePeer,
            PeerRegistration localPeer,
            ShipmentProposal proposal
    ) {
        if (sourcePeer == null || !sourcePeer.active() || localPeer == null || !localPeer.active()) {
            throw forbidden("Both source and local peers must be active");
        }
        if (sourcePeer.peerId().equals(localPeer.peerId())) {
            throw forbidden("A peer cannot relay a shipment endorsement to itself");
        }
        if (sourcePeer.peerId().equals(localPeer.peerId())) {
            throw forbidden("A peer cannot relay a shipment proposal to itself");
        }
        if (proposal == null || proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER) {
            throw new IllegalArgumentException("relayed shipment proposal must await carrier endorsement");
        }
        validateEnvelope(proposal);
        if (!sourcePeer.organizationId().equals(field(proposal.event(), "senderOrganizationId"))) {
            throw forbidden("Authenticated peer does not own the proposal sender organization");
        }
        if (!localPeer.organizationId().equals(field(proposal.event(), "carrierOrganizationId"))) {
            throw forbidden("Shipment proposal is addressed to a different carrier organization");
        }
        validateProposalAgainstCanonicalState(null, proposal);
        boolean inserted = insertOrMatch(proposal);
        ShipmentProposal stored = repository.find(proposal.proposalId()).orElse(proposal);
        return new ReceiveResult(stored, inserted);
    }

    public ShipmentProposal endorse(
            AuthenticatedAccount actor,
            String proposalId,
            SignatureEnvelope carrierSignature
    ) {
        requireOrganizationAccount(actor);
        ShipmentProposal proposal = repository.find(proposalId)
                .orElseThrow(() -> new ShipmentProposalNotFoundException(proposalId));
        if (!"CARRIER".equals(actor.role())) {
            throw forbidden("A carrier account is required to endorse this proposal");
        }
        if (proposal.status() == ShipmentProposal.Status.SUBMITTED) {
            if (actor.organizationId().equals(field(proposal.event(), "carrierOrganizationId"))) {
                endorsementRelayer.relay(proposal, proposal.event());
                return proposal;
            }
            throw forbidden("Only the selected carrier may endorse this proposal");
        }
        if (proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER
                && proposal.status() != ShipmentProposal.Status.READY) {
            throw new IllegalArgumentException("shipment proposal is not available for endorsement");
        }
        if (!actor.organizationId().equals(field(proposal.event(), "carrierOrganizationId"))) {
            throw forbidden("Only the selected carrier may endorse this proposal");
        }
        if (proposal.status() == ShipmentProposal.Status.AWAITING_CARRIER
                && !clock.instant().isBefore(proposal.expiresAt())) {
            repository.markExpired(proposalId);
            throw new IllegalArgumentException("shipment proposal has expired");
        }
        if (carrierSignature == null
                || !actor.organizationId().equals(carrierSignature.organizationId())
                || !"SHIPMENT_CARRIER".equals(carrierSignature.purpose())) {
            throw forbidden("Carrier endorsement must be signed by the selected carrier");
        }
        if (proposal.status() == ShipmentProposal.Status.READY
                && !proposal.event().signatures().get(1).equals(carrierSignature)) {
            throw forbidden("Proposal already has a different carrier endorsement");
        }

        ShipmentProposal endorsed = proposal;
        if (proposal.status() == ShipmentProposal.Status.AWAITING_CARRIER) {
            BlockValidationContext context = validateProposalAgainstCanonicalState(actor, proposal);
            verifySignature(proposal.event(), carrierSignature, context);
            endorsed = repository.endorse(proposalId, carrierSignature);
        }
        BatchEvent signedEvent = withTransactionId(endorsed.event(), endorsed.event().signatures());
        localTransactionSubmitter.submit(signedEvent);
        ShipmentProposal submitted = new ShipmentProposal(
                endorsed.proposalId(),
                signedEvent,
                endorsed.expiresAt(),
                ShipmentProposal.Status.SUBMITTED,
                signedEvent.transactionId());
        repository.markSubmitted(proposalId, signedEvent.transactionId());
        endorsementRelayer.relay(proposal, signedEvent);
        return submitted;
    }

    public ShipmentProposal receiveEndorsementFromPeer(
            PeerRegistration sourcePeer,
            PeerRegistration localPeer,
            String proposalId,
            BatchEvent signedEvent
    ) {
        if (sourcePeer == null || !sourcePeer.active() || localPeer == null || !localPeer.active()) {
            throw forbidden("Both source and local peers must be active");
        }
        ShipmentProposal proposal = repository.find(proposalId)
                .orElseThrow(() -> new ShipmentProposalNotFoundException(proposalId));
        if (!sourcePeer.organizationId().equals(field(proposal.event(), "carrierOrganizationId"))
                || !localPeer.organizationId().equals(field(proposal.event(), "senderOrganizationId"))) {
            throw forbidden("Peer identities do not match the shipment parties");
        }
        if (signedEvent == null || !proposalId.equals(proposal.proposalId())
                || !proposal.event().eventId().equals(signedEvent.eventId())
                || !TransactionCodec.payloadJson(networkId, proposal.event())
                        .equals(TransactionCodec.payloadJson(networkId, signedEvent))) {
            throw new IllegalArgumentException("Endorsement does not match the stored proposal payload");
        }
        SignatureEnvelope senderSignature = proposal.event().signatures().get(0);
        SignatureEnvelope carrierSignature = signedEvent.signatures().stream()
                .filter(signature -> "SHIPMENT_CARRIER".equals(signature.purpose()))
                .filter(signature -> sourcePeer.organizationId().equals(signature.organizationId()))
                .findFirst()
                .orElseThrow(() -> forbidden("Relayed transaction lacks the selected carrier signature"));
        if (!signedEvent.signatures().contains(senderSignature)
                || signedEvent.signatures().size() != 2
                || !TransactionCodec.transactionId(networkId, signedEvent)
                        .equals(signedEvent.transactionId())) {
            throw new IllegalArgumentException("Relayed SHIPPED transaction is not canonical");
        }
        if (proposal.status() == ShipmentProposal.Status.SUBMITTED) {
            if (!proposal.submittedTransactionId().equals(signedEvent.transactionId())) {
                throw forbidden("A different shipment transaction was already submitted");
            }
            return proposal;
        }
        if (proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER
                && proposal.status() != ShipmentProposal.Status.READY) {
            throw new IllegalArgumentException("shipment proposal is not ready for endorsement");
        }
        BlockValidationContext context = validateProposalAgainstCanonicalState(null, proposal);
        verifySignature(proposal.event(), carrierSignature, context);
        blockchain.validateTransactionsForNextBlock(List.of(signedEvent));
        ShipmentProposal endorsed = proposal.status() == ShipmentProposal.Status.READY
                ? proposal
                : repository.endorse(proposalId, carrierSignature);
        if (!endorsed.event().signatures().equals(signedEvent.signatures())) {
            throw new IllegalArgumentException("Relayed carrier endorsement differs from stored signature");
        }
        peerTransactionSubmitter.submit(signedEvent);
        repository.markSubmitted(proposalId, signedEvent.transactionId());
        return new ShipmentProposal(
                proposalId, signedEvent, proposal.expiresAt(),
                ShipmentProposal.Status.SUBMITTED, signedEvent.transactionId());
    }

    public Optional<ShipmentProposal> find(AuthenticatedAccount actor, String proposalId) {
        requireOrganizationAccount(actor);
        ShipmentProposal proposal = repository.find(proposalId).orElse(null);
        if (proposal == null) {
            return Optional.empty();
        }
        if (!isParticipant(actor, proposal)) {
            throw forbidden("Account is not a participant in this shipment proposal");
        }
        return Optional.of(expireIfNeeded(proposal));
    }

    public List<ShipmentProposal> inbox(AuthenticatedAccount actor) {
        requireOrganizationAccount(actor);
        if (!"CARRIER".equals(actor.role())) {
            throw forbidden("Only carrier accounts may view the shipment inbox");
        }
        return repository.findUnexpiredForCarrier(actor.organizationId());
    }

    public String payloadHash(ShipmentProposal proposal) {
        return TransactionCodec.payloadHash(networkId, proposal.event());
    }

    private ShipmentProposal expireIfNeeded(ShipmentProposal proposal) {
        if (proposal.status() == ShipmentProposal.Status.AWAITING_CARRIER
                && !clock.instant().isBefore(proposal.expiresAt())) {
            repository.markExpired(proposal.proposalId());
            return new ShipmentProposal(
                    proposal.proposalId(), proposal.event(), proposal.expiresAt(),
                    ShipmentProposal.Status.EXPIRED, null);
        }
        return proposal;
    }

    private void validateEnvelope(ShipmentProposal proposal) {
        requireUuid(proposal.proposalId(), "proposalId");
        requireUuid(proposal.event().eventId(), "eventId");
        String expiryField = field(proposal.event(), "expiresAt");
        if (!proposal.expiresAt().equals(parseExpiry(expiryField))) {
            throw new IllegalArgumentException("expiresAt must match the signed proposal payload");
        }
        if (proposal.expiresAt().getNano() % 1_000 != 0) {
            throw new IllegalArgumentException("expiresAt precision must not exceed microseconds");
        }
        if (!clock.instant().isBefore(proposal.expiresAt())) {
            throw new IllegalArgumentException("proposal expiry must be in the future");
        }
        if (!proposal.event().eventTime().isBefore(proposal.expiresAt())) {
            throw new IllegalArgumentException("eventTime must precede proposal expiry");
        }
    }

    private boolean insertOrMatch(ShipmentProposal proposal) {
        try {
            repository.insert(proposal);
            return true;
        } catch (DuplicateTransactionException exception) {
            ShipmentProposal existing = repository.find(proposal.proposalId()).orElse(null);
            if (existing != null && sameImmutableProposal(existing, proposal)) {
                return false;
            }
            throw exception;
        }
    }

    private boolean sameImmutableProposal(ShipmentProposal left, ShipmentProposal right) {
        return left.proposalId().equals(right.proposalId())
                && left.expiresAt().equals(right.expiresAt())
                && TransactionCodec.payloadJson(networkId, left.event())
                        .equals(TransactionCodec.payloadJson(networkId, right.event()))
                && left.event().signatures().equals(right.event().signatures());
    }

    private BlockValidationContext validateProposalAgainstCanonicalState(
            AuthenticatedAccount actor,
            ShipmentProposal proposal
    ) {
        Blockchain.ValidatedCanonicalChain chain = blockchain.loadValidatedCanonicalChainSnapshot();
        BlockValidationContext context = chain.snapshot().nextBlockContext();
        BatchEvent event = proposal.event();
        BatchSnapshot batch = context.batches().get(event.batchCode());
        if (batch == null || (batch.state() != BatchState.PACKAGED && batch.state() != BatchState.RECEIVED)) {
            throw new IllegalArgumentException("batch must be packaged or received before shipment");
        }
        String senderId = field(event, "senderOrganizationId");
        String carrierId = field(event, "carrierOrganizationId");
        String recipientId = field(event, "recipientOrganizationId");
        field(event, "fromProvince");
        field(event, "toProvince");
        if (!batch.currentHolderOrganizationId().equals(senderId)) {
            throw forbidden("Only the current batch holder may create a shipment proposal");
        }
        if (senderId.equals(carrierId) || senderId.equals(recipientId) || carrierId.equals(recipientId)) {
            throw new IllegalArgumentException("shipment sender, carrier, and recipient must be distinct");
        }
        var registry = context.governanceRegistry();
        GovernedOrganization sender = activeOrganization(registry.organizations().get(senderId), senderId);
        GovernedOrganization carrier = activeOrganization(registry.organizations().get(carrierId), carrierId);
        GovernedOrganization recipient = activeOrganization(registry.organizations().get(recipientId), recipientId);
        if (sender.type() != OrganizationType.FARMER
                && sender.type() != OrganizationType.WAREHOUSE
                && sender.type() != OrganizationType.RETAILER) {
            throw new IllegalArgumentException("organization is not allowed to send a batch");
        }
        if (carrier.type() != OrganizationType.CARRIER) {
            throw new IllegalArgumentException("selected organization is not a carrier");
        }
        if (recipient.type() != OrganizationType.WAREHOUSE
                && recipient.type() != OrganizationType.RETAILER) {
            throw new IllegalArgumentException("shipment recipient must be a warehouse or retailer");
        }
        if (actor != null) {
            GovernedOrganization actorOrganization = activeOrganization(
                    registry.organizations().get(actor.organizationId()), actor.organizationId());
            if (!actorRole(actorOrganization.type()).equals(actor.role())) {
                throw forbidden("Authenticated account role does not match its organization");
            }
        }
        SignatureEnvelope senderSignature = proposal.event().signatures().get(0);
        if (!senderId.equals(senderSignature.organizationId())
                || !"SHIPMENT_SENDER".equals(senderSignature.purpose())) {
            throw forbidden("Proposal must contain the sender's SHIPMENT_SENDER signature");
        }
        verifySignature(event, senderSignature, context);
        return context;
    }

    private void verifySignature(
            BatchEvent event,
            SignatureEnvelope signature,
            BlockValidationContext context
    ) {
        long inclusionHeight = context.parent() == null ? 0 : context.parent().header().height() + 1;
        OrganizationKey key = context.governanceRegistry().organizationKeys().get(signature.keyId());
        if (key == null || !key.organizationId().equals(signature.organizationId())
                || !key.isValidAt(inclusionHeight)) {
            throw forbidden("Signature key is not active for the proposal signer");
        }
        try {
            if (!SignatureUtil.verifyP256Sha256(
                    TransactionCodec.signingBytes(networkId, event, signature),
                    signature.signatureBytes(),
                    key.publicKeyBytes())) {
                throw forbidden("Shipment proposal signature is invalid");
            }
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("Shipment proposal signature could not be verified", exception);
        }
    }

    private BatchEvent withTransactionId(BatchEvent event, List<SignatureEnvelope> signatures) {
        BatchEvent unsignedId = new BatchEvent(
                "pending", event.eventId(), event.batchCode(), EventType.SHIPPED,
                event.eventTime(), event.data(), signatures);
        String transactionId = TransactionCodec.transactionId(networkId, unsignedId);
        return new BatchEvent(
                transactionId, event.eventId(), event.batchCode(), EventType.SHIPPED,
                event.eventTime(), event.data(), signatures);
    }

    private boolean isParticipant(AuthenticatedAccount actor, ShipmentProposal proposal) {
        return actor.organizationId().equals(field(proposal.event(), "senderOrganizationId"))
                || actor.organizationId().equals(field(proposal.event(), "carrierOrganizationId"))
                || actor.organizationId().equals(field(proposal.event(), "recipientOrganizationId"));
    }

    private GovernedOrganization activeOrganization(GovernedOrganization organization, String id) {
        if (organization == null || !id.equals(organization.organizationId())
                || organization.status() != OrganizationStatus.ACTIVE) {
            throw new IllegalArgumentException("shipment party is not an active organization");
        }
        return organization;
    }

    private String field(BatchEvent event, String name) {
        Object value = event.data().get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(name + " must be a non-blank string");
        }
        return text;
    }

    private void requireUuid(String value, String name) {
        try {
            if (!UUID.fromString(value).toString().equalsIgnoreCase(value)) {
                throw new IllegalArgumentException(name + " must be a UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(name + " must be a UUID", exception);
        }
    }

    private Instant parseExpiry(String value) {
        try {
            if (!value.endsWith("Z")) {
                throw new java.time.format.DateTimeParseException(
                        "Timestamp must use UTC", value, value.length());
            }
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException exception) {
            throw new IllegalArgumentException("expiresAt must be a UTC ISO-8601 timestamp", exception);
        }
    }

    private void requireOrganizationAccount(AuthenticatedAccount actor) {
        if (actor == null) {
            throw new AuthenticationException("UNAUTHENTICATED", "Authentication is required", 401);
        }
        if (actor.organizationId() == null || actor.organizationId().isBlank()) {
            throw forbidden("An organization account is required");
        }
    }

    private AuthenticationException forbidden(String message) {
        return new AuthenticationException("FORBIDDEN", message, 403);
    }

    private String actorRole(OrganizationType type) {
        return type.name();
    }

    @FunctionalInterface
    interface ShipmentTransactionSubmitter {
        void submit(BatchEvent event);
    }

    public record ReceiveResult(ShipmentProposal proposal, boolean inserted) {
        public ReceiveResult {
            Objects.requireNonNull(proposal, "proposal");
        }
    }
}
