package service;

import java.util.Objects;
import network.PeerAuthenticator;
import network.PeerIdentity;

public final class NodeRuntime {
    public static final String SERVLET_CONTEXT_ATTRIBUTE =
            NodeRuntime.class.getName() + ".runtime";

    private final TransactionService transactionService;
    private final GovernanceService governanceService;
    private final BatchService batchService;
    private final TraceabilityService traceabilityService;
    private final ShipmentProposalService shipmentProposalService;
    private final PeerLedgerService peerLedgerService;
    private final PeerIdentity peerIdentity;
    private final PeerAuthenticator peerAuthenticator;

    public NodeRuntime(
            TransactionService transactionService,
            GovernanceService governanceService,
            BatchService batchService,
            TraceabilityService traceabilityService,
            ShipmentProposalService shipmentProposalService,
            PeerLedgerService peerLedgerService,
            PeerIdentity peerIdentity,
            PeerAuthenticator peerAuthenticator
    ) {
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService");
        this.governanceService = Objects.requireNonNull(governanceService, "governanceService");
        this.batchService = Objects.requireNonNull(batchService, "batchService");
        this.traceabilityService = Objects.requireNonNull(
                traceabilityService, "traceabilityService");
        this.shipmentProposalService = Objects.requireNonNull(
                shipmentProposalService, "shipmentProposalService");
        this.peerLedgerService = Objects.requireNonNull(peerLedgerService, "peerLedgerService");
        this.peerIdentity = Objects.requireNonNull(peerIdentity, "peerIdentity");
        this.peerAuthenticator = Objects.requireNonNull(peerAuthenticator, "peerAuthenticator");
    }

    public TransactionService transactionService() {
        return transactionService;
    }

    public GovernanceService governanceService() {
        return governanceService;
    }

    public BatchService batchService() {
        return batchService;
    }

    public TraceabilityService traceabilityService() {
        return traceabilityService;
    }

    public ShipmentProposalService shipmentProposalService() {
        return shipmentProposalService;
    }

    public PeerLedgerService peerLedgerService() {
        return peerLedgerService;
    }

    public PeerIdentity peerIdentity() {
        return peerIdentity;
    }

    public PeerAuthenticator peerAuthenticator() {
        return peerAuthenticator;
    }
}
