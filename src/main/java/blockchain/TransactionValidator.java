package blockchain;

import java.security.GeneralSecurityException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import model.BatchEvent;
import model.Organization;
import model.OrganizationKey;
import model.SignatureEnvelope;

public final class TransactionValidator {
    private final String networkId;

    public TransactionValidator(String networkId) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        this.networkId = networkId;
    }

    public ValidatedTransaction validate(
            BatchEvent event,
            long inclusionHeight,
            Map<String, Organization> organizations,
            Map<String, OrganizationKey> organizationKeys
    ) {
        if (event == null) {
            throw invalid("INVALID_TRANSACTION", "Transaction event must not be null");
        }
        if (inclusionHeight < 0) {
            throw invalid("INVALID_HEIGHT", "Inclusion height must not be negative");
        }
        Map<String, Organization> knownOrganizations = organizations == null ? Map.of() : organizations;
        Map<String, OrganizationKey> knownKeys = organizationKeys == null ? Map.of() : organizationKeys;
        if (event.signatures().isEmpty()) {
            throw invalid("MISSING_SIGNATURES", "Transaction must contain at least one organization signature");
        }

        String payloadHash;
        String transactionId;
        try {
            payloadHash = TransactionCodec.payloadHash(networkId, event);
            transactionId = TransactionCodec.transactionId(networkId, event);
        } catch (IllegalArgumentException exception) {
            throw new TransactionValidationException(
                    "INVALID_TRANSACTION_PAYLOAD", "Transaction payload cannot be canonically encoded", exception);
        }
        if (!transactionId.equals(event.transactionId())) {
            throw invalid("TRANSACTION_ID_MISMATCH", "Transaction ID does not match its canonical envelope");
        }

        Set<String> signerOrganizations = new HashSet<>();
        for (SignatureEnvelope signature : event.signatures()) {
            if (!signerOrganizations.add(signature.organizationId())) {
                throw invalid("DUPLICATE_SIGNER", "An organization may sign a transaction only once");
            }
            Organization organization = knownOrganizations.get(signature.organizationId());
            if (organization == null) {
                throw invalid("UNKNOWN_ORGANIZATION", "Signer organization is not registered");
            }
            if (!organization.organizationId().equals(signature.organizationId())) {
                throw invalid("UNKNOWN_ORGANIZATION", "Organization registry entry does not match the signer");
            }
            if (!organization.active()) {
                throw invalid("INACTIVE_ORGANIZATION", "Signer organization is not active");
            }

            OrganizationKey key = knownKeys.get(signature.keyId());
            if (key == null
                    || !key.keyId().equals(signature.keyId())
                    || !key.organizationId().equals(signature.organizationId())) {
                throw invalid("UNKNOWN_ORGANIZATION_KEY", "Signing key is not registered to the signer");
            }
            if (!key.isValidAt(inclusionHeight)) {
                throw invalid("INACTIVE_ORGANIZATION_KEY", "Signing key is not valid at the inclusion height");
            }
            verifySignature(event, signature, key);
        }
        return new ValidatedTransaction(transactionId, payloadHash);
    }

    private void verifySignature(BatchEvent event, SignatureEnvelope signature, OrganizationKey key) {
        try {
            boolean valid = SignatureUtil.verifyP256Sha256(
                    TransactionCodec.signingBytes(networkId, event, signature),
                    signature.signatureBytes(),
                    key.publicKeyBytes());
            if (!valid) {
                throw invalid("INVALID_SIGNATURE", "Organization signature does not match the transaction");
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new TransactionValidationException(
                    "INVALID_SIGNATURE", "Organization signature or public key is invalid", exception);
        }
    }

    private TransactionValidationException invalid(String code, String message) {
        return new TransactionValidationException(code, message);
    }
}
