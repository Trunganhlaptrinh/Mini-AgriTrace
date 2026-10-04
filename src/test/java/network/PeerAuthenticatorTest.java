package network;

import blockchain.BlockRepository;
import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.GovernanceRegistry;
import blockchain.HashUtil;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import model.GovernedOrganization;
import model.OrganizationStatus;
import model.OrganizationType;
import model.PeerRegistration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerAuthenticatorTest {
    private static final String ACTIVE_FINGERPRINT = "a".repeat(64);

    @Test
    void authenticatesOnlyAnActivePeerOfAnActiveOrganization() {
        PeerRegistration registration = new PeerRegistration(
                "peer-farm", "farm-1", "https://farm.example/AgriTrace",
                ACTIVE_FINGERPRINT, true);
        PeerAuthenticator authenticator = new PeerAuthenticator(blockchain(new GovernanceRegistry(
                Map.of("farm-1", organization("farm-1", OrganizationStatus.ACTIVE)),
                Map.of(),
                Map.of(registration.peerId(), registration))));

        assertEquals(registration, authenticator.authenticateFingerprint(ACTIVE_FINGERPRINT));
        byte[] certificateBytes = {1, 2, 3};
        PeerRegistration certificateRegistration = new PeerRegistration(
                "peer-certificate", "farm-1", "https://farm.example/AgriTrace",
                HashUtil.sha256Hex(certificateBytes), true);
        PeerAuthenticator certificateAuthenticator = new PeerAuthenticator(blockchain(
                new GovernanceRegistry(
                        Map.of("farm-1", organization("farm-1", OrganizationStatus.ACTIVE)),
                        Map.of(),
                        Map.of(certificateRegistration.peerId(), certificateRegistration))));
        assertEquals(certificateRegistration, certificateAuthenticator.authenticate(
                new X509Certificate[]{new EncodedCertificate(certificateBytes)}));
    }

    @Test
    void rejectsUnknownInactiveAmbiguousAndMalformedPeerCertificates() {
        PeerRegistration inactivePeer = new PeerRegistration(
                "peer-inactive", "farm-1", "https://farm.example/AgriTrace",
                "b".repeat(64), false);
        PeerRegistration suspendedOrganizationPeer = new PeerRegistration(
                "peer-suspended-org", "farm-2", "https://farm2.example/AgriTrace",
                "c".repeat(64), true);
        PeerAuthenticator authenticator = new PeerAuthenticator(blockchain(new GovernanceRegistry(
                Map.of(
                        "farm-1", organization("farm-1", OrganizationStatus.ACTIVE),
                        "farm-2", organization("farm-2", OrganizationStatus.SUSPENDED)),
                Map.of(),
                Map.of(
                        inactivePeer.peerId(), inactivePeer,
                        suspendedOrganizationPeer.peerId(), suspendedOrganizationPeer))));

        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticateFingerprint(ACTIVE_FINGERPRINT));
        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticateFingerprint("B".repeat(64)));
        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticateFingerprint("d".repeat(64)));
        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticate(new X509Certificate[0]));
        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticate(new X509Certificate[]{null}));
    }

    @Test
    void rejectsDuplicateFingerprintRegistrations() {
        PeerRegistration first = new PeerRegistration(
                "peer-one", "farm-1", "https://one.example/AgriTrace",
                ACTIVE_FINGERPRINT, true);
        PeerRegistration second = new PeerRegistration(
                "peer-two", "farm-2", "https://two.example/AgriTrace",
                ACTIVE_FINGERPRINT, true);
        PeerAuthenticator authenticator = new PeerAuthenticator(blockchain(new GovernanceRegistry(
                Map.of(
                        "farm-1", organization("farm-1", OrganizationStatus.ACTIVE),
                        "farm-2", organization("farm-2", OrganizationStatus.ACTIVE)),
                Map.of(),
                Map.of(first.peerId(), first, second.peerId(), second))));

        assertThrows(PeerAuthenticationException.class,
                () -> authenticator.authenticateFingerprint(ACTIVE_FINGERPRINT));
    }

    private GovernedOrganization organization(String id, OrganizationStatus status) {
        return new GovernedOrganization(id, OrganizationType.FARMER, id, null, status);
    }

    private Blockchain blockchain(GovernanceRegistry registry) {
        BlockRepository repository = new BlockRepository() {
            @Override
            public StoreResult storeValidatedBlock(BlockValidationResult validation) {
                throw new UnsupportedOperationException("This test does not store blocks");
            }

            @Override
            public List<StoredBlock> loadCanonicalChain() {
                return List.of();
            }

            @Override
            public List<StoredBlock> loadBranch(String tipHash) {
                return List.of();
            }
        };
        return new Blockchain(
                new BlockValidator("peer-auth-test", 1, "0".repeat(64)),
                repository,
                BlockValidationContext.genesis(registry));
    }

    private static final class EncodedCertificate extends X509Certificate {
        private final byte[] encoded;

        private EncodedCertificate(byte[] encoded) {
            this.encoded = encoded.clone();
        }

        @Override
        public byte[] getEncoded() throws CertificateEncodingException {
            return encoded.clone();
        }

        @Override public void checkValidity() { }
        @Override public void checkValidity(Date date) { }
        @Override public int getVersion() { return 3; }
        @Override public BigInteger getSerialNumber() { return BigInteger.ONE; }
        @Override public Principal getIssuerDN() { return () -> "CN=test"; }
        @Override public Principal getSubjectDN() { return () -> "CN=test"; }
        @Override public Date getNotBefore() { return new Date(0); }
        @Override public Date getNotAfter() { return new Date(Long.MAX_VALUE); }
        @Override public byte[] getTBSCertificate() { return new byte[0]; }
        @Override public byte[] getSignature() { return new byte[0]; }
        @Override public String getSigAlgName() { return "none"; }
        @Override public String getSigAlgOID() { return "0.0"; }
        @Override public byte[] getSigAlgParams() { return null; }
        @Override public boolean[] getIssuerUniqueID() { return null; }
        @Override public boolean[] getSubjectUniqueID() { return null; }
        @Override public boolean[] getKeyUsage() { return null; }
        @Override public int getBasicConstraints() { return -1; }
        @Override public void verify(PublicKey key) { }
        @Override public void verify(PublicKey key, String sigProvider) { }
        @Override public String toString() { return "test certificate"; }
        @Override public PublicKey getPublicKey() { return null; }
        @Override public Set<String> getCriticalExtensionOIDs() { return null; }
        @Override public Set<String> getNonCriticalExtensionOIDs() { return null; }
        @Override public byte[] getExtensionValue(String oid) { return null; }
        @Override public boolean hasUnsupportedCriticalExtension() { return false; }
    }
}
