package network;

import blockchain.Blockchain;
import blockchain.HashUtil;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.List;
import blockchain.GovernanceRegistry;
import model.GovernedOrganization;
import model.OrganizationStatus;
import model.PeerRegistration;

public final class PeerAuthenticator {
    private final Blockchain blockchain;

    public PeerAuthenticator(Blockchain blockchain) {
        this.blockchain = java.util.Objects.requireNonNull(blockchain, "blockchain");
    }

    public PeerRegistration authenticate(X509Certificate[] certificateChain) {
        if (certificateChain == null || certificateChain.length == 0 || certificateChain[0] == null) {
            throw new PeerAuthenticationException("A mutual-TLS peer certificate is required");
        }
        try {
            return authenticateFingerprint(HashUtil.sha256Hex(certificateChain[0].getEncoded()));
        } catch (CertificateEncodingException exception) {
            throw new PeerAuthenticationException("Peer certificate cannot be encoded", exception);
        }
    }

    public PeerRegistration authenticateFingerprint(String fingerprint) {
        if (fingerprint == null || !fingerprint.matches("[0-9a-f]{64}")) {
            throw new PeerAuthenticationException("Peer certificate fingerprint is invalid");
        }
        GovernanceRegistry registry = blockchain.loadValidatedCanonicalChainSnapshot()
                .snapshot().nextBlockContext().governanceRegistry();
        List<PeerRegistration> matches = registry.peers().values().stream()
                .filter(peer -> peer.active()
                        && fingerprint.equals(peer.tlsCertificateFingerprint()))
                .filter(peer -> hasActiveOrganization(registry, peer))
                .toList();
        if (matches.size() != 1) {
            throw new PeerAuthenticationException(
                    matches.isEmpty()
                            ? "Peer certificate is not authorized by the canonical registry"
                            : "Peer certificate fingerprint is registered more than once");
        }
        return matches.get(0);
    }

    private boolean hasActiveOrganization(GovernanceRegistry registry, PeerRegistration peer) {
        GovernedOrganization organization = registry.organizations().get(peer.organizationId());
        return organization != null && organization.status() == OrganizationStatus.ACTIVE;
    }
}
