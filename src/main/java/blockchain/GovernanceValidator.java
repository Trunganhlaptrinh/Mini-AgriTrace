package blockchain;

import java.security.GeneralSecurityException;
import model.GovernanceTransaction;

public final class GovernanceValidator {
    private final String networkId;
    private final byte[] genesisAdminPublicKey;

    public GovernanceValidator(String networkId, byte[] genesisAdminPublicKey) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        if (genesisAdminPublicKey == null) {
            throw new IllegalArgumentException("genesisAdminPublicKey must not be null");
        }
        try {
            SignatureUtil.validateP256PublicKey(genesisAdminPublicKey);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("genesisAdminPublicKey must be a valid P-256 SPKI key", exception);
        }
        this.networkId = networkId;
        this.genesisAdminPublicKey = genesisAdminPublicKey.clone();
    }

    public GovernanceRegistry validateAndApply(
            GovernanceTransaction transaction,
            long inclusionHeight,
            GovernanceRegistry parentRegistry
    ) {
        if (transaction == null || parentRegistry == null) {
            throw invalid("INVALID_GOVERNANCE_TRANSACTION", "Transaction and parent registry are required");
        }
        if (inclusionHeight < 0) {
            throw invalid("INVALID_HEIGHT", "Inclusion height must not be negative");
        }
        try {
            String transactionId = GovernanceCodec.transactionId(networkId, transaction);
            if (!transactionId.equals(transaction.transactionId())) {
                throw invalid("TRANSACTION_ID_MISMATCH", "Governance transaction ID does not match its envelope");
            }
            boolean validSignature = SignatureUtil.verifyP256Sha256(
                    GovernanceCodec.signingBytes(networkId, transaction),
                    transaction.adminSignatureBytes(),
                    genesisAdminPublicKey);
            if (!validSignature) {
                throw invalid("INVALID_ADMIN_SIGNATURE", "Governance signature does not match genesis admin key");
            }
        } catch (GovernanceValidationException exception) {
            throw exception;
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new GovernanceValidationException(
                    "INVALID_GOVERNANCE_SIGNATURE", "Governance signature or envelope is invalid", exception);
        }
        return parentRegistry.apply(transaction, inclusionHeight);
    }

    private GovernanceValidationException invalid(String code, String message) {
        return new GovernanceValidationException(code, message);
    }
}
