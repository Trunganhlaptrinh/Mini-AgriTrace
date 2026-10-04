package network;

import blockchain.BlockValidationContext;
import blockchain.Blockchain;
import blockchain.HashUtil;
import config.PeerIdentityConfiguration;
import java.io.InputStream;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.SecureRandom;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Enumeration;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import model.GovernedOrganization;
import model.OrganizationStatus;
import model.PeerRegistration;

public final class PeerIdentity {
    private final PeerRegistration registration;
    private final SSLContext sslContext;

    private PeerIdentity(PeerRegistration registration, SSLContext sslContext) {
        this.registration = registration;
        this.sslContext = sslContext;
    }

    public static PeerIdentity loadRequired(
            PeerIdentityConfiguration configuration,
            Blockchain blockchain
    ) {
        if (configuration == null || blockchain == null) {
            throw new IllegalArgumentException("peer configuration and blockchain are required");
        }
        char[] password = configuration.keyStorePassword();
        try {
            KeyStore keyStore = KeyStore.getInstance("PKCS12");
            try (InputStream stream = Files.newInputStream(configuration.keyStorePath())) {
                keyStore.load(stream, password);
            }
            String alias = singlePrivateKeyAlias(keyStore);
            Certificate certificate = keyStore.getCertificate(alias);
            if (!(certificate instanceof X509Certificate x509Certificate)) {
                throw new IllegalStateException("P2P client key must have an X.509 certificate");
            }
            String fingerprint = fingerprint(x509Certificate);
            BlockValidationContext context =
                    blockchain.loadValidatedCanonicalChainSnapshot()
                            .snapshot().nextBlockContext();
            PeerRegistration registration =
                    activeRegistration(context, configuration.peerId(), fingerprint);

            KeyManagerFactory keyManagers =
                    KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keyManagers.init(keyStore, password);
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(keyManagers.getKeyManagers(), null, new SecureRandom());
            java.util.Arrays.fill(password, '\0');
            return new PeerIdentity(registration, sslContext);
        } catch (java.io.IOException | GeneralSecurityException exception) {
            java.util.Arrays.fill(password, '\0');
            throw new IllegalStateException("Could not initialize the configured P2P client certificate", exception);
        } catch (RuntimeException exception) {
            java.util.Arrays.fill(password, '\0');
            throw exception;
        }
    }

    public PeerRegistration registration() {
        return registration;
    }

    public SSLContext sslContext() {
        return sslContext;
    }

    public java.net.http.HttpClient httpClient() {
        return java.net.http.HttpClient.newBuilder()
                .sslContext(sslContext)
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(java.net.http.HttpClient.Redirect.NEVER)
                .version(java.net.http.HttpClient.Version.HTTP_1_1)
                .build();
    }

    public static String fingerprint(X509Certificate certificate) {
        try {
            return HashUtil.sha256Hex(certificate.getEncoded());
        } catch (CertificateEncodingException exception) {
            throw new IllegalArgumentException("Peer certificate cannot be encoded", exception);
        }
    }

    private static String singlePrivateKeyAlias(KeyStore keyStore) throws KeyStoreException {
        String foundAlias = null;
        Enumeration<String> aliases = keyStore.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (keyStore.isKeyEntry(alias)) {
                if (foundAlias != null) {
                    throw new IllegalStateException(
                            "P2P PKCS#12 keystore must contain exactly one private-key entry");
                }
                foundAlias = alias;
            }
        }
        if (foundAlias == null) {
            throw new IllegalStateException("P2P PKCS#12 keystore contains no private-key entry");
        }
        return foundAlias;
    }

    private static PeerRegistration activeRegistration(
            BlockValidationContext context,
            String peerId,
            String fingerprint
    ) {
        PeerRegistration registration = context.governanceRegistry().peers().get(peerId);
        if (registration == null || !registration.active()) {
            throw new IllegalStateException("Configured local peer is not active in the canonical registry");
        }
        if (!registration.tlsCertificateFingerprint().equals(fingerprint)) {
            throw new IllegalStateException(
                    "Configured P2P certificate does not match the canonical peer registration");
        }
        GovernedOrganization organization =
                context.governanceRegistry().organizations().get(registration.organizationId());
        if (organization == null || organization.status() != OrganizationStatus.ACTIVE) {
            throw new IllegalStateException("Configured local peer organization is not active");
        }
        return registration;
    }
}
