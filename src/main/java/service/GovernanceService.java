package service;

import blockchain.GovernanceCodec;
import blockchain.GovernanceValidationException;
import blockchain.SignatureUtil;
import config.NetworkConfiguration;
import dal.TransactionDAO;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import model.GovernanceTransaction;
import model.GovernanceType;

public final class GovernanceService {
    private final NetworkConfiguration networkConfiguration;
    private final GovernanceTransactionSubmitter transactionSubmitter;

    public GovernanceService(
            NetworkConfiguration networkConfiguration,
            TransactionService transactionService
    ) {
        this(networkConfiguration, transactionService::submit);
    }

    GovernanceService(
            NetworkConfiguration networkConfiguration,
            GovernanceTransactionSubmitter transactionSubmitter
    ) {
        this.networkConfiguration = Objects.requireNonNull(
                networkConfiguration, "networkConfiguration");
        this.transactionSubmitter = Objects.requireNonNull(
                transactionSubmitter, "transactionSubmitter");
    }

    public RegistrationResult registerOrganization(
            String eventId,
            Instant eventTime,
            String organizationId,
            String organizationType,
            String name,
            String province,
            String keyId,
            String algorithm,
            String publicKey,
            String adminSignature
    ) {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("organizationId", organizationId);
        data.put("organizationType", organizationType);
        data.put("name", name);
        data.put("keyId", keyId);
        data.put("algorithm", algorithm);
        data.put("publicKey", publicKey);
        if (province != null) {
            data.put("province", province);
        }
        return submit(GovernanceType.REGISTER_ORGANIZATION, eventId, eventTime, data, adminSignature);
    }

    public RegistrationResult registerOrganizationKey(
            String eventId,
            Instant eventTime,
            String organizationId,
            String keyId,
            String algorithm,
            String publicKey,
            String adminSignature
    ) {
        return submit(GovernanceType.REGISTER_ORGANIZATION_KEY, eventId, eventTime, Map.of(
                "organizationId", organizationId,
                "keyId", keyId,
                "algorithm", algorithm,
                "publicKey", publicKey), adminSignature);
    }

    public RegistrationResult revokeOrganizationKey(
            String eventId,
            Instant eventTime,
            String keyId,
            String adminSignature
    ) {
        return submit(GovernanceType.REVOKE_ORGANIZATION_KEY, eventId, eventTime,
                Map.of("keyId", keyId), adminSignature);
    }

    public RegistrationResult setOrganizationStatus(
            String eventId,
            Instant eventTime,
            String organizationId,
            String status,
            String adminSignature
    ) {
        return submit(GovernanceType.SET_ORGANIZATION_STATUS, eventId, eventTime, Map.of(
                "organizationId", organizationId,
                "status", status), adminSignature);
    }

    public RegistrationResult registerPeer(
            String eventId,
            Instant eventTime,
            String peerId,
            String organizationId,
            String endpoint,
            String tlsCertificateFingerprint,
            String adminSignature
    ) {
        return submit(GovernanceType.REGISTER_PEER, eventId, eventTime, Map.of(
                "peerId", peerId,
                "organizationId", organizationId,
                "endpoint", endpoint,
                "tlsCertificateFingerprint", tlsCertificateFingerprint), adminSignature);
    }

    public RegistrationResult revokePeer(
            String eventId,
            Instant eventTime,
            String peerId,
            String adminSignature
    ) {
        return submit(GovernanceType.REVOKE_PEER, eventId, eventTime,
                Map.of("peerId", peerId), adminSignature);
    }

    private RegistrationResult submit(
            GovernanceType governanceType,
            String eventId,
            Instant eventTime,
            Map<String, String> data,
            String adminSignature
    ) {
        GovernanceTransaction unsigned = new GovernanceTransaction(
                "pending",
                eventId,
                governanceType,
                eventTime,
                data,
                adminSignature);
        verifyAdministratorSignature(unsigned);
        GovernanceTransaction transaction = new GovernanceTransaction(
                GovernanceCodec.transactionId(networkConfiguration.networkId(), unsigned),
                unsigned.eventId(),
                unsigned.governanceType(),
                unsigned.eventTime(),
                unsigned.data(),
                unsigned.adminSignature());
        TransactionDAO.SubmissionResult submissionResult = transactionSubmitter.submit(transaction);
        return new RegistrationResult(transaction.transactionId(), submissionResult);
    }

    private void verifyAdministratorSignature(GovernanceTransaction transaction) {
        try {
            if (!SignatureUtil.verifyP256Sha256(
                    GovernanceCodec.signingBytes(networkConfiguration.networkId(), transaction),
                    transaction.adminSignatureBytes(),
                    networkConfiguration.genesisAdminPublicKeyBytes())) {
                throw new GovernanceValidationException(
                        "INVALID_ADMIN_SIGNATURE",
                        "Governance signature does not match the configured genesis administrator key");
            }
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            throw new GovernanceValidationException(
                    "INVALID_GOVERNANCE_SIGNATURE",
                    "Governance signature or envelope is invalid",
                    exception);
        }
    }

    public record RegistrationResult(
            String transactionId,
            TransactionDAO.SubmissionResult submissionResult
    ) {
        public RegistrationResult {
            if (transactionId == null || !transactionId.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("transactionId must be a lowercase SHA-256 digest");
            }
            Objects.requireNonNull(submissionResult, "submissionResult");
        }

        public boolean inserted() {
            return submissionResult == TransactionDAO.SubmissionResult.INSERTED;
        }
    }

    @FunctionalInterface
    interface GovernanceTransactionSubmitter {
        TransactionDAO.SubmissionResult submit(GovernanceTransaction transaction);
    }
}
