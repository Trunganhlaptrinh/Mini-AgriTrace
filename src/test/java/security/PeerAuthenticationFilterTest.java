package security;

import blockchain.BlockRepository;
import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.GovernanceRegistry;
import blockchain.HashUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.security.Principal;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import model.GovernedOrganization;
import model.OrganizationStatus;
import model.OrganizationType;
import model.PeerRegistration;
import network.PeerAuthenticator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerAuthenticationFilterTest {
    private static final String CERTIFICATE_ATTRIBUTE =
            "jakarta.servlet.request.X509Certificate";

    @Test
    void rejectsRequestsWithoutClientCertificate() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        PeerAuthenticationFilter filter = new PeerAuthenticationFilter(authenticator());

        filter.doFilter(request(Map.of()), response.proxy(), chain(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.status);
        assertTrue(response.body().contains("PEER_CERTIFICATE_REQUIRED"));
    }

    @Test
    void rejectsCertificateNotRegisteredByGovernance() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        PeerAuthenticationFilter filter = new PeerAuthenticationFilter(authenticator());
        X509Certificate unknownCertificate = new EncodedCertificate(new byte[]{8, 9, 10});

        filter.doFilter(
                request(Map.of(CERTIFICATE_ATTRIBUTE, new X509Certificate[]{unknownCertificate})),
                response.proxy(),
                chain(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
        assertTrue(response.body().contains("PEER_NOT_AUTHORIZED"));
    }

    @Test
    void authenticatesRegisteredCertificateAndAttachesPeerToRequest() throws Exception {
        byte[] certificateBytes = {1, 3, 5, 7};
        PeerRegistration expectedPeer = new PeerRegistration(
                "peer-1", "farm-1", "https://farm.example/AgriTrace",
                HashUtil.sha256Hex(certificateBytes), true);
        PeerAuthenticator authenticator = authenticator(expectedPeer);
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        AtomicReference<Object> authenticatedPeer = new AtomicReference<>();
        HttpServletRequest request = request(Map.of(
                CERTIFICATE_ATTRIBUTE,
                new X509Certificate[]{new EncodedCertificate(certificateBytes)}));

        new PeerAuthenticationFilter(authenticator).doFilter(
                request,
                response.proxy(),
                (servletRequest, servletResponse) -> {
                    chainCalled.set(true);
                    authenticatedPeer.set(servletRequest.getAttribute(
                            PeerAuthenticationFilter.AUTHENTICATED_PEER_ATTRIBUTE));
                });

        assertTrue(chainCalled.get());
        assertEquals(0, response.status);
        assertSame(expectedPeer, authenticatedPeer.get());
    }

    @Test
    void reportsUnavailableRuntimeWhenNoAuthenticatorIsConfigured() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();

        new PeerAuthenticationFilter().doFilter(
                request(Map.of()),
                response.proxy(),
                chain(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(HttpServletResponse.SC_SERVICE_UNAVAILABLE, response.status);
        assertTrue(response.body().contains("PEER_RUNTIME_UNAVAILABLE"));
    }

    private HttpServletRequest request(Map<String, Object> attributes) {
        Map<String, Object> requestAttributes = new java.util.HashMap<>(attributes);
        ServletContext context = proxy(ServletContext.class, (method, args) -> switch (method) {
            case "getAttribute" -> null;
            case "log" -> null;
            default -> null;
        });
        return proxy(HttpServletRequest.class, (method, args) -> switch (method) {
            case "getServletContext" -> context;
            case "getAttribute" -> requestAttributes.get(args[0]);
            case "setAttribute" -> {
                requestAttributes.put((String) args[0], args[1]);
                yield null;
            }
            default -> null;
        });
    }

    private FilterChain chain(AtomicBoolean called) {
        return (request, response) -> called.set(true);
    }

    @Test
    void rejectsInactiveOrRevokedPeerCertificate() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        byte[] certificateBytes = {1, 3, 5, 7};
        PeerRegistration inactivePeer = new PeerRegistration(
                "peer-1", "farm-1", "https://farm.example/AgriTrace",
                HashUtil.sha256Hex(certificateBytes), false);
        PeerAuthenticator authenticator = authenticator(inactivePeer);
        PeerAuthenticationFilter filter = new PeerAuthenticationFilter(authenticator);

        filter.doFilter(
                request(Map.of(CERTIFICATE_ATTRIBUTE, new X509Certificate[]{new EncodedCertificate(certificateBytes)})),
                response.proxy(),
                chain(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
        assertTrue(response.body().contains("PEER_NOT_AUTHORIZED"));
    }

    @Test
    void rejectsPeerCertificateWhenOrganizationIsSuspended() throws Exception {
        FakeResponse response = new FakeResponse();
        AtomicBoolean chainCalled = new AtomicBoolean();
        byte[] certificateBytes = {1, 3, 5, 7};
        PeerRegistration peer = new PeerRegistration(
                "peer-1", "farm-1", "https://farm.example/AgriTrace",
                HashUtil.sha256Hex(certificateBytes), true);
        GovernedOrganization suspendedOrg = new GovernedOrganization(
                "farm-1", OrganizationType.FARMER, "Farm", null, OrganizationStatus.SUSPENDED);
        PeerAuthenticator authenticator = authenticator(peer, suspendedOrg);
        PeerAuthenticationFilter filter = new PeerAuthenticationFilter(authenticator);

        filter.doFilter(
                request(Map.of(CERTIFICATE_ATTRIBUTE, new X509Certificate[]{new EncodedCertificate(certificateBytes)})),
                response.proxy(),
                chain(chainCalled));

        assertFalse(chainCalled.get());
        assertEquals(HttpServletResponse.SC_FORBIDDEN, response.status);
        assertTrue(response.body().contains("PEER_NOT_AUTHORIZED"));
    }

    private PeerAuthenticator authenticator() {
        byte[] registeredCertificate = {1, 3, 5, 7};
        return authenticator(new PeerRegistration(
                "peer-1", "farm-1", "https://farm.example/AgriTrace",
                HashUtil.sha256Hex(registeredCertificate), true));
    }

    private PeerAuthenticator authenticator(PeerRegistration peer) {
        return authenticator(peer, new GovernedOrganization(
                "farm-1", OrganizationType.FARMER, "Farm", null, OrganizationStatus.ACTIVE));
    }

    private PeerAuthenticator authenticator(PeerRegistration peer, GovernedOrganization organization) {
        GovernanceRegistry registry = new GovernanceRegistry(
                Map.of(organization.organizationId(), organization),
                Map.of(),
                Map.of(peer.peerId(), peer));
        BlockRepository emptyRepository = new BlockRepository() {
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
        Blockchain blockchain = new Blockchain(
                new BlockValidator("peer-filter-test", 1, "0".repeat(64)),
                emptyRepository,
                BlockValidationContext.genesis(registry));
        return new PeerAuthenticator(blockchain);
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }

    private static final class FakeResponse {
        private final StringWriter writer = new StringWriter();
        private int status;

        private HttpServletResponse proxy() {
            return PeerAuthenticationFilterTest.proxy(
                    HttpServletResponse.class, (method, args) -> switch (method) {
                        case "setStatus" -> {
                            status = (Integer) args[0];
                            yield null;
                        }
                        case "setCharacterEncoding", "setContentType" -> null;
                        case "getWriter" -> new PrintWriter(writer);
                        default -> null;
                    });
        }

        private String body() {
            return writer.toString();
        }
    }

    private static final class EncodedCertificate extends X509Certificate {
        private final byte[] encoded;

        private EncodedCertificate(byte[] encoded) {
            this.encoded = encoded.clone();
        }

        @Override public byte[] getEncoded() throws CertificateEncodingException { return encoded.clone(); }
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
