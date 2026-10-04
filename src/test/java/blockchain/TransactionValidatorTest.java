package blockchain;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.BatchEvent;
import model.EventType;
import model.Organization;
import model.OrganizationKey;
import model.OrganizationType;
import model.SignatureEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransactionValidatorTest {
    private static final String NETWORK_ID = "agritrace-test";

    private final TransactionValidator validator = new TransactionValidator(NETWORK_ID);
    private KeyPair keyPair;
    private Organization organization;
    private OrganizationKey organizationKey;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        keyPair = generator.generateKeyPair();
        organization = new Organization("farm-1", OrganizationType.FARMER, true);
        organizationKey = new OrganizationKey(
                "farm-key-1",
                organization.organizationId(),
                Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded()),
                5,
                9L);
    }

    @Test
    void validatesCanonicalTransactionAndRegisteredSignature() throws Exception {
        BatchEvent event = signedHarvest();

        ValidatedTransaction result = validator.validate(
                event, 5, Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey));

        assertEquals(event.transactionId(), result.transactionId());
        assertEquals(TransactionCodec.payloadHash(NETWORK_ID, event), result.payloadHash());
    }

    @Test
    void verifiesSignatureCreatedByWebCrypto() throws Exception {
        JsonObject vector;
        try (var input = getClass().getResourceAsStream("/transaction-webcrypto-vector.json")) {
            vector = JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        }
        SignatureEnvelope signature = envelope(
                "farm-1", "farm-key-1", vector.get("signatureP1363Base64").getAsString());
        BatchEvent event = withCanonicalTransactionId(unsignedWithSignatures(List.of(signature)));
        OrganizationKey vectorKey = new OrganizationKey(
                "farm-key-1",
                "farm-1",
                vector.get("publicKeySpkiBase64").getAsString(),
                5,
                null);
        String canonicalSigningPayload = new String(
                TransactionCodec.signingBytes(NETWORK_ID, event, signature),
                StandardCharsets.UTF_8);

        assertEquals(vector.get("canonicalSigningPayload").getAsString(), canonicalSigningPayload);
        assertEquals(vector.get("payloadHashHex").getAsString(), TransactionCodec.payloadHash(NETWORK_ID, event));
        assertEquals(vector.get("transactionId").getAsString(), event.transactionId());
        assertEquals(event.transactionId(), validator.validate(
                event, 5, Map.of("farm-1", organization), Map.of("farm-key-1", vectorKey)).transactionId());
    }

    @Test
    void rejectsPayloadTamperingEvenWhenTheTransactionIdIsRecomputed() throws Exception {
        BatchEvent original = signedHarvest();
        BatchEvent tamperedPayload = new BatchEvent(
                "pending",
                original.eventId(),
                original.batchCode(),
                original.eventType(),
                original.eventTime(),
                Map.of("productType", "Papaya"),
                original.signatures());
        BatchEvent tampered = withCanonicalTransactionId(tamperedPayload);

        TransactionValidationException exception = assertThrows(TransactionValidationException.class,
                () -> validator.validate(tampered, 5,
                        Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey)));

        assertEquals("INVALID_SIGNATURE", exception.getCode());
    }

    @Test
    void rejectsATransactionIdThatDoesNotMatchItsEnvelope() throws Exception {
        BatchEvent valid = signedHarvest();
        BatchEvent tamperedId = new BatchEvent(
                "0".repeat(64),
                valid.eventId(),
                valid.batchCode(),
                valid.eventType(),
                valid.eventTime(),
                valid.data(),
                valid.signatures());

        TransactionValidationException exception = assertThrows(TransactionValidationException.class,
                () -> validator.validate(tamperedId, 5,
                        Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey)));

        assertEquals("TRANSACTION_ID_MISMATCH", exception.getCode());
    }

    @Test
    void rejectsDuplicateOrganizationSignatures() throws Exception {
        BatchEvent original = signedHarvest();
        SignatureEnvelope signature = original.signatures().get(0);
        BatchEvent duplicateSignatures = withCanonicalTransactionId(new BatchEvent(
                "pending",
                original.eventId(),
                original.batchCode(),
                original.eventType(),
                original.eventTime(),
                original.data(),
                List.of(signature, signature)));

        TransactionValidationException exception = assertThrows(TransactionValidationException.class,
                () -> validator.validate(duplicateSignatures, 5,
                        Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey)));

        assertEquals("DUPLICATE_SIGNER", exception.getCode());
    }

    @Test
    void rejectsAKeyRegisteredToAnotherOrganization() throws Exception {
        BatchEvent event = signedHarvest();
        OrganizationKey unrelatedKey = new OrganizationKey(
                "farm-key-1",
                "another-org",
                organizationKey.publicKey(),
                5,
                null);

        TransactionValidationException exception = assertThrows(TransactionValidationException.class,
                () -> validator.validate(event, 5,
                        Map.of("farm-1", organization), Map.of("farm-key-1", unrelatedKey)));

        assertEquals("UNKNOWN_ORGANIZATION_KEY", exception.getCode());
    }

    @Test
    void enforcesKeyActivationAndRevocationHeights() throws Exception {
        BatchEvent event = signedHarvest();

        TransactionValidationException notYetValid = assertThrows(TransactionValidationException.class,
                () -> validator.validate(event, 4,
                        Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey)));
        TransactionValidationException revoked = assertThrows(TransactionValidationException.class,
                () -> validator.validate(event, 9,
                        Map.of("farm-1", organization), Map.of("farm-key-1", organizationKey)));

        assertEquals("INACTIVE_ORGANIZATION_KEY", notYetValid.getCode());
        assertEquals("INACTIVE_ORGANIZATION_KEY", revoked.getCode());
    }

    @Test
    void transactionIdDoesNotDependOnSignatureInputOrder() {
        SignatureEnvelope first = envelope("farm-1", "key-1", encodedSignature((byte) 0));
        SignatureEnvelope second = envelope("carrier-1", "key-2", encodedSignature((byte) 1));
        BatchEvent one = unsignedWithSignatures(List.of(first, second));
        BatchEvent reversed = unsignedWithSignatures(List.of(second, first));

        assertEquals(TransactionCodec.transactionId(NETWORK_ID, one),
                TransactionCodec.transactionId(NETWORK_ID, reversed));
        assertTrue(TransactionCodec.payloadHash(NETWORK_ID, one).matches("[0-9a-f]{64}"));
    }

    @Test
    void encodesEventTimeAtTheBrowserProtocolMillisecondPrecision() {
        BatchEvent event = unsignedWithSignatures(List.of(
                envelope("farm-1", "key-1", encodedSignature((byte) 0))));
        String signingPayload = new String(
                TransactionCodec.signingBytes(NETWORK_ID, event, event.signatures().get(0)),
                java.nio.charset.StandardCharsets.UTF_8);

        assertTrue(signingPayload.contains("\"eventTime\":\"2026-10-01T02:00:00.000Z\""));
    }

    @Test
    void rejectsEventTimesWithSubMillisecondPrecision() {
        BatchEvent event = new BatchEvent(
                "pending",
                "event-1",
                "MANGO-2026-0001",
                EventType.HARVESTED,
                Instant.parse("2026-10-01T02:00:00.000000001Z"),
                Map.of("productType", "Mango"),
                List.of(envelope("farm-1", "key-1", encodedSignature((byte) 0))));

        assertThrows(IllegalArgumentException.class, () -> TransactionCodec.payloadHash(NETWORK_ID, event));
    }

    @Test
    void defensivelyCopiesNestedEventData() {
        Map<String, Object> nestedObject = new HashMap<>();
        nestedObject.put("field", "before");
        BatchEvent event = new BatchEvent(
                "pending",
                "event-1",
                "MANGO-2026-0001",
                EventType.CORRECTION,
                Instant.parse("2026-10-01T02:00:00Z"),
                Map.of("correctedPublicData", nestedObject),
                List.of(envelope("farm-1", "key-1", encodedSignature((byte) 0))));
        nestedObject.put("field", "after");

        Map<?, ?> copiedObject = (Map<?, ?>) event.data().get("correctedPublicData");
        assertEquals("before", copiedObject.get("field"));
        assertThrows(UnsupportedOperationException.class, copiedObject::clear);
    }

    private BatchEvent signedHarvest() throws Exception {
        BatchEvent unsigned = unsignedWithSignatures(List.of(envelope(
                organization.organizationId(), organizationKey.keyId(), Base64.getEncoder()
                        .encodeToString(new byte[64]))));
        SignatureEnvelope placeholder = unsigned.signatures().get(0);
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(TransactionCodec.signingBytes(NETWORK_ID, unsigned, placeholder));
        SignatureEnvelope signed = envelope(
                organization.organizationId(),
                organizationKey.keyId(),
                Base64.getEncoder().encodeToString(derToP1363(signer.sign())));
        return withCanonicalTransactionId(unsignedWithSignatures(List.of(signed)));
    }

    private BatchEvent unsignedWithSignatures(List<SignatureEnvelope> signatures) {
        return new BatchEvent(
                "pending",
                "event-1",
                "MANGO-2026-0001",
                EventType.HARVESTED,
                Instant.parse("2026-10-01T02:00:00Z"),
                Map.of("productType", "Mango", "quantity", "1200.000"),
                signatures);
    }

    private BatchEvent withCanonicalTransactionId(BatchEvent event) {
        String transactionId = TransactionCodec.transactionId(NETWORK_ID, event);
        return new BatchEvent(
                transactionId,
                event.eventId(),
                event.batchCode(),
                event.eventType(),
                event.eventTime(),
                event.data(),
                event.signatures());
    }

    private SignatureEnvelope envelope(String organizationId, String keyId, String signature) {
        return new SignatureEnvelope(organizationId, keyId, "FARMER_HARVEST", signature);
    }

    private String encodedSignature(byte value) {
        byte[] signature = new byte[64];
        Arrays.fill(signature, value);
        return Base64.getEncoder().encodeToString(signature);
    }

    private byte[] derToP1363(byte[] der) {
        int offset = 2;
        int rLength = der[offset + 1] & 0xff;
        BigInteger r = new BigInteger(1, java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + rLength));
        offset += rLength + 2;
        int sLength = der[offset + 1] & 0xff;
        BigInteger s = new BigInteger(1, java.util.Arrays.copyOfRange(der, offset + 2, offset + 2 + sLength));
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
