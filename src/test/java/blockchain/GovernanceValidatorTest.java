package blockchain;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.OrganizationStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GovernanceValidatorTest {
    private static final String NETWORK_ID = "agritrace-test";
    private final GovernanceRegistry emptyRegistry = GovernanceRegistry.empty();
    private KeyPair adminKeyPair;
    private KeyPair farmerKeyPair;
    private GovernanceValidator validator;

    @BeforeEach
    void setUp() throws Exception {
        adminKeyPair = keyPair();
        farmerKeyPair = keyPair();
        validator = new GovernanceValidator(NETWORK_ID, adminKeyPair.getPublic().getEncoded());
    }

    @Test
    void validatesGenesisAdminSignatureAndRegistersOrganizationAndFirstKeyAtInclusionHeight()
            throws Exception {
        GovernanceTransaction transaction = signed(
                GovernanceType.REGISTER_ORGANIZATION, registrationData(), adminKeyPair);

        GovernanceRegistry registry = validator.validateAndApply(transaction, 12, emptyRegistry);

        assertEquals("Mekong Mango Farm", registry.organizations().get("farm-1").name());
        assertEquals(OrganizationStatus.ACTIVE, registry.organizations().get("farm-1").status());
        assertTrue(registry.organizationKeys().get("farm-key-1").isValidAt(12));
        assertFalse(registry.organizationKeys().containsKey("farm-key-1-copy"));
    }

    @Test
    void acceptsGovernanceSignatureAndHashesFromTheWebCryptoProtocolVector() throws Exception {
        String adminPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE7nX/JA2YfGSysJeqenLw9b6h4pRQ3rKfymHiJ0UTffY0PMjTnaQjoHdPZaOSVspa66K6W/IoajGhgb0/JTK18Q==";
        String organizationPublicKey = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEM9TzrY9EMvcgIYeFEtB+dn+C5cBj1Y+Tv207DRZlqT2GcHwWDk0D16VbO4V4sgCWpo2MfDuopazC07eF2cH6aQ==";
        String signature = "DU2M7TtvlXdkvTZZR8wqFmYBfkNA0NNvwcYO+3AWi6emkELCH2ZxwDjzGeK+5R+aD1Oe0ezJQca9BS/9SjIOwQ==";
        Map<String, String> data = Map.of(
                "organizationId", "org-farm-001",
                "organizationType", "FARMER",
                "name", "Mekong Mango Farm",
                "province", "Tien Giang",
                "keyId", "org-farm-001-key-1",
                "algorithm", "ECDSA_P256_SHA256",
                "publicKey", organizationPublicKey);
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                "11111111-2222-4333-8444-555555555555",
                GovernanceType.REGISTER_ORGANIZATION,
                Instant.parse("2026-10-04T10:00:00.000Z"),
                data,
                signature);
        GovernanceTransaction transaction = new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, unsigned),
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                data,
                signature);
        GovernanceValidator webCryptoValidator = new GovernanceValidator(
                NETWORK_ID, Base64.getDecoder().decode(adminPublicKey));

        assertEquals(
                "81f0bd30879008ded336717a0bc42763e5d8c237804360df76b270ac3035b219",
                GovernanceCodec.payloadHash(NETWORK_ID, transaction));
        assertEquals(
                "9540f5491f4713aa114503259e6ad56d516ce49084c8bc40da7a5329bc07d09f",
                transaction.transactionId());
        assertEquals(
                "org-farm-001",
                webCryptoValidator.validateAndApply(transaction, 1, emptyRegistry)
                        .organizations().get("org-farm-001").organizationId());
    }

    @Test
    void rejectsGovernanceTransactionNotSignedByTheGenesisAdmin() throws Exception {
        GovernanceTransaction transaction = signed(
                GovernanceType.REGISTER_ORGANIZATION, registrationData(), farmerKeyPair);

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(transaction, 1, emptyRegistry));

        assertEquals("INVALID_ADMIN_SIGNATURE", exception.getCode());
    }

    @Test
    void rejectsAnAdminSignatureWhenTheGovernancePayloadIsChanged() throws Exception {
        GovernanceTransaction signed = signed(
                GovernanceType.REGISTER_ORGANIZATION, registrationData(), adminKeyPair);
        GovernanceTransaction changed = new GovernanceTransaction(
                signed.transactionId(),
                signed.eventId(),
                signed.governanceType(),
                signed.eventTime(),
                Map.of(
                        "organizationId", "farm-1",
                        "organizationType", "FARMER",
                        "name", "Changed Farm",
                        "keyId", "farm-key-1",
                        "algorithm", "ECDSA_P256_SHA256",
                        "publicKey", Base64.getEncoder().encodeToString(farmerKeyPair.getPublic().getEncoded())),
                signed.adminSignature());

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(changed, 1, emptyRegistry));

        assertEquals("TRANSACTION_ID_MISMATCH", exception.getCode());
    }

    @Test
    void appliesOrganizationStatusAndPreventsReactivatingARevokedOrganization() throws Exception {
        GovernanceRegistry registered = registerOrganization();
        GovernanceTransaction suspend = signed(
                GovernanceType.SET_ORGANIZATION_STATUS,
                Map.of("organizationId", "farm-1", "status", "SUSPENDED"),
                adminKeyPair);
        GovernanceRegistry suspended = validator.validateAndApply(suspend, 5, registered);
        GovernanceTransaction revoke = signed(
                GovernanceType.SET_ORGANIZATION_STATUS,
                Map.of("organizationId", "farm-1", "status", "REVOKED"),
                adminKeyPair);
        GovernanceRegistry revoked = validator.validateAndApply(revoke, 6, suspended);
        GovernanceTransaction reactivate = signed(
                GovernanceType.SET_ORGANIZATION_STATUS,
                Map.of("organizationId", "farm-1", "status", "ACTIVE"),
                adminKeyPair);

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(reactivate, 7, revoked));

        assertEquals("REVOKED_ORGANIZATION_TERMINAL", exception.getCode());
        assertFalse(revoked.organizationValidationView().get("farm-1").active());
    }

    @Test
    void revokesAKeyAtTheGovernanceTransactionHeightWithoutDeletingHistory() throws Exception {
        GovernanceRegistry registered = registerOrganization();
        GovernanceTransaction revoke = signed(
                GovernanceType.REVOKE_ORGANIZATION_KEY,
                Map.of("keyId", "farm-key-1"),
                adminKeyPair);

        GovernanceRegistry revoked = validator.validateAndApply(revoke, 15, registered);
        var historicalKey = revoked.organizationKeys().get("farm-key-1");

        assertEquals(15, historicalKey.revokedAtHeight());
        assertTrue(historicalKey.isValidAt(14));
        assertFalse(historicalKey.isValidAt(15));
    }

    @Test
    void registersAndRevokesPeersWithValidatedHttpsFingerprint() throws Exception {
        GovernanceRegistry registered = registerOrganization();
        GovernanceTransaction registerPeer = signed(
                GovernanceType.REGISTER_PEER,
                Map.of(
                        "peerId", "node-farm-1",
                        "organizationId", "farm-1",
                        "endpoint", "https://10.0.0.12:8443",
                        "tlsCertificateFingerprint", "a".repeat(64)),
                adminKeyPair);
        GovernanceRegistry withPeer = validator.validateAndApply(registerPeer, 14, registered);
        GovernanceTransaction revokePeer = signed(
                GovernanceType.REVOKE_PEER,
                Map.of("peerId", "node-farm-1"),
                adminKeyPair);

        GovernanceRegistry withoutPeer = validator.validateAndApply(revokePeer, 15, withPeer);

        assertTrue(withPeer.peers().get("node-farm-1").active());
        assertFalse(withoutPeer.peers().get("node-farm-1").active());
    }

    @Test
    void rejectsPeerWithoutAnActiveOrganizationOrValidEndpoint() throws Exception {
        GovernanceRegistry registered = registerOrganization();
        GovernanceTransaction invalidEndpoint = signed(
                GovernanceType.REGISTER_PEER,
                Map.of(
                        "peerId", "node-farm-1",
                        "organizationId", "farm-1",
                        "endpoint", "http://10.0.0.12:8443",
                        "tlsCertificateFingerprint", "a".repeat(64)),
                adminKeyPair);

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(invalidEndpoint, 14, registered));

        assertEquals("INVALID_PEER_ENDPOINT", exception.getCode());
    }

    @Test
    void rejectsRegistrationWithNonP256PublicKey() throws Exception {
        Map<String, String> invalidKeyData = new java.util.HashMap<>(registrationData());
        invalidKeyData.put("publicKey", Base64.getEncoder().encodeToString(new byte[64]));
        GovernanceTransaction transaction = signed(
                GovernanceType.REGISTER_ORGANIZATION, invalidKeyData, adminKeyPair);

        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(transaction, 1, emptyRegistry));

        assertEquals("INVALID_ORGANIZATION_PUBLIC_KEY", exception.getCode());
    }

    @Test
    void rejectsDuplicateOrganizationAndKeyIdentifiersAndUnchangedStatus() throws Exception {
        GovernanceRegistry registered = registerOrganization();

        assertRejected(
                GovernanceType.REGISTER_ORGANIZATION,
                registrationData(),
                registered,
                "ORGANIZATION_ALREADY_REGISTERED");
        assertRejected(
                GovernanceType.REGISTER_ORGANIZATION_KEY,
                Map.of(
                        "organizationId", "farm-1",
                        "keyId", "farm-key-1",
                        "algorithm", "ECDSA_P256_SHA256",
                        "publicKey", Base64.getEncoder().encodeToString(farmerKeyPair.getPublic().getEncoded())),
                registered,
                "ORGANIZATION_KEY_ALREADY_REGISTERED");
        assertRejected(
                GovernanceType.SET_ORGANIZATION_STATUS,
                Map.of("organizationId", "farm-1", "status", "ACTIVE"),
                registered,
                "ORGANIZATION_STATUS_UNCHANGED");
    }

    @Test
    void rejectsBlankProvinceAndOversizedOrganizationName() throws Exception {
        Map<String, String> blankProvince = new java.util.HashMap<>(registrationData());
        blankProvince.put("province", " ");
        assertRejected(
                GovernanceType.REGISTER_ORGANIZATION,
                blankProvince,
                emptyRegistry,
                "INVALID_GOVERNANCE_DATA");

        Map<String, String> oversizedName = new java.util.HashMap<>(registrationData());
        oversizedName.put("name", "x".repeat(201));
        assertRejected(
                GovernanceType.REGISTER_ORGANIZATION,
                oversizedName,
                emptyRegistry,
                "GOVERNANCE_FIELD_TOO_LONG");
    }

    @Test
    void rejectsPeerRegistrationForSuspendedOrganizationsAndInvalidFingerprints() throws Exception {
        GovernanceRegistry registered = registerOrganization();
        GovernanceTransaction suspend = signed(
                GovernanceType.SET_ORGANIZATION_STATUS,
                Map.of("organizationId", "farm-1", "status", "SUSPENDED"),
                adminKeyPair);
        GovernanceRegistry suspended = validator.validateAndApply(suspend, 5, registered);
        Map<String, String> peerData = Map.of(
                "peerId", "node-farm-1",
                "organizationId", "farm-1",
                "endpoint", "https://10.0.0.12:8443",
                "tlsCertificateFingerprint", "a".repeat(64));

        assertRejected(
                GovernanceType.REGISTER_PEER,
                peerData,
                suspended,
                "INACTIVE_ORGANIZATION");
        Map<String, String> invalidFingerprint = new java.util.HashMap<>(peerData);
        invalidFingerprint.put("tlsCertificateFingerprint", "A".repeat(64));
        assertRejected(
                GovernanceType.REGISTER_PEER,
                invalidFingerprint,
                registered,
                "INVALID_PEER_FINGERPRINT");
    }

    private GovernanceRegistry registerOrganization() throws Exception {
        return validator.validateAndApply(
                signed(GovernanceType.REGISTER_ORGANIZATION, registrationData(), adminKeyPair),
                4,
                emptyRegistry);
    }

    private void assertRejected(
            GovernanceType type,
            Map<String, String> data,
            GovernanceRegistry registry,
            String expectedCode
    ) throws Exception {
        GovernanceTransaction transaction = signed(type, data, adminKeyPair);
        GovernanceValidationException exception = assertThrows(
                GovernanceValidationException.class,
                () -> validator.validateAndApply(transaction, 8, registry));
        assertEquals(expectedCode, exception.getCode());
    }

    private Map<String, String> registrationData() {
        return Map.of(
                "organizationId", "farm-1",
                "organizationType", "FARMER",
                "name", "Mekong Mango Farm",
                "province", "Tien Giang",
                "keyId", "farm-key-1",
                "algorithm", "ECDSA_P256_SHA256",
                "publicKey", Base64.getEncoder().encodeToString(farmerKeyPair.getPublic().getEncoded()));
    }

    private GovernanceTransaction signed(
            GovernanceType type,
            Map<String, String> data,
            KeyPair signer
    ) throws Exception {
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                UUID.randomUUID().toString(),
                type,
                Instant.parse("2026-10-04T10:00:00Z"),
                data,
                Base64.getEncoder().encodeToString(new byte[64]));
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(signer.getPrivate());
        signature.update(GovernanceCodec.signingBytes(NETWORK_ID, unsigned));
        GovernanceTransaction signed = new GovernanceTransaction(
                "pending",
                unsigned.eventId(),
                type,
                unsigned.eventTime(),
                data,
                Base64.getEncoder().encodeToString(derToP1363(signature.sign())));
        return new GovernanceTransaction(
                GovernanceCodec.transactionId(NETWORK_ID, signed),
                signed.eventId(),
                type,
                signed.eventTime(),
                data,
                signed.adminSignature());
    }

    private KeyPair keyPair() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private byte[] derToP1363(byte[] der) {
        int offset = 2;
        int rLength = der[offset + 1] & 0xff;
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(
                der, offset + 2, offset + 2 + rLength));
        offset += rLength + 2;
        int sLength = der[offset + 1] & 0xff;
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(
                der, offset + 2, offset + 2 + sLength));
        byte[] p1363 = new byte[64];
        copyInteger(r, p1363, 0);
        copyInteger(s, p1363, 32);
        return p1363;
    }

    private void copyInteger(BigInteger value, byte[] target, int offset) {
        byte[] bytes = value.toByteArray();
        int sourceOffset = bytes.length > 32 ? bytes.length - 32 : 0;
        int length = bytes.length - sourceOffset;
        System.arraycopy(bytes, sourceOffset, target, offset + 32 - length, length);
    }
}
