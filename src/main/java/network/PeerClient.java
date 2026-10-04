package network;

import blockchain.Blockchain;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.logging.Logger;
import model.GovernedOrganization;
import model.OrganizationStatus;
import model.PeerRegistration;
import model.ShipmentProposal;

public final class PeerClient implements ShipmentProposalRelayer, ShipmentEndorsementRelayer {
    private static final Logger LOGGER = Logger.getLogger(PeerClient.class.getName());
    private static final String SHIPMENT_PROPOSAL_PATH =
            "/api/v1/internal/p2p/shipment-proposals";
    private final Blockchain blockchain;
    private final PeerIdentity identity;
    private final java.net.http.HttpClient httpClient;

    public PeerClient(Blockchain blockchain, PeerIdentity identity) {
        this.blockchain = java.util.Objects.requireNonNull(blockchain, "blockchain");
        this.identity = java.util.Objects.requireNonNull(identity, "identity");
        this.httpClient = identity.httpClient();
    }

    @Override
    public void relay(ShipmentProposal proposal) {
        String carrierId = value(proposal, "carrierOrganizationId");
        String body = ShipmentProposalWireCodec.encode(proposal);
        deliver(peersForOrganization(carrierId), body,
                destination -> proposalUri(destination.endpoint()), false);
    }

    @Override
    public void relay(ShipmentProposal proposal, model.BatchEvent signedEvent) {
        String senderId = value(proposal, "senderOrganizationId");
        String body = ShipmentEndorsementWireCodec.encode(proposal.proposalId(), signedEvent);
        boolean senderIsLocal = identity.registration().organizationId().equals(senderId);
        deliver(peersForOrganization(senderId), body,
                destination -> endorsementUri(destination.endpoint(), proposal.proposalId()),
                senderIsLocal);
    }

    private List<PeerRegistration> peersForOrganization(String organizationId) {
        var registry = blockchain.loadValidatedCanonicalChainSnapshot()
                .snapshot().nextBlockContext().governanceRegistry();
        return registry.peers().values().stream()
                .filter(PeerRegistration::active)
                .filter(peer -> !peer.peerId().equals(identity.registration().peerId()))
                .filter(peer -> organizationId.equals(peer.organizationId()))
                .filter(peer -> {
                    GovernedOrganization organization = registry.organizations()
                            .get(peer.organizationId());
                    return organization != null && organization.status() == OrganizationStatus.ACTIVE;
                })
                .toList();
    }

    private void deliver(
            List<PeerRegistration> destinations,
            String body,
            java.util.function.Function<PeerRegistration, URI> uriFactory,
            boolean localCopyIsSufficient
    ) {
        if (destinations.isEmpty()) {
            if (localCopyIsSufficient) {
                return;
            }
            throw new PeerDeliveryException(
                    "No active peer is registered for the shipment organization");
        }
        int delivered = 0;
        for (PeerRegistration destination : destinations) {
            try {
                HttpRequest request = HttpRequest.newBuilder(uriFactory.apply(destination))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<Void> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                if (response.statusCode() == 200 || response.statusCode() == 202) {
                    delivered++;
                } else {
                    LOGGER.warning("Shipment proposal relay to peer " + destination.peerId()
                            + " returned HTTP " + response.statusCode());
                }
            } catch (IOException exception) {
                LOGGER.warning("Shipment proposal relay to peer " + destination.peerId()
                        + " failed: " + exception.getClass().getSimpleName());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                LOGGER.warning("Shipment proposal relay was interrupted");
                break;
            }
        }
        if (delivered == 0 && !localCopyIsSufficient) {
            throw new PeerDeliveryException("Shipment message could not be delivered to an authorized peer");
        }
        if (delivered == 0 && localCopyIsSufficient) {
            LOGGER.warning("Shipment endorsement remains stored on the local sender peer; "
                    + "registered sibling peers did not respond");
        }
    }

    private URI proposalUri(String endpoint) {
        return internalUri(endpoint, SHIPMENT_PROPOSAL_PATH);
    }

    private URI endorsementUri(String endpoint, String proposalId) {
        return internalUri(endpoint, SHIPMENT_PROPOSAL_PATH + "/" + proposalId + "/endorsements");
    }

    private URI internalUri(String endpoint, String internalPath) {
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
        String path = base.getPath() == null ? "" : base.getPath();
        String normalizedPath = path.replaceAll("/+$", "") + internalPath;
        try {
            return new URI(base.getScheme(), null, base.getHost(), base.getPort(),
                    normalizedPath, null, null);
        } catch (java.net.URISyntaxException exception) {
            throw new PeerDeliveryException("Registered peer endpoint cannot be resolved");
        } catch (IllegalArgumentException exception) {
            throw new PeerDeliveryException("Registered peer endpoint cannot be resolved");
        }
    }

    private String value(ShipmentProposal proposal, String field) {
        Object value = proposal.event().data().get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Shipment proposal is missing " + field);
        }
        return text;
    }
}
