package blockchain;

import java.net.URI;
import java.net.URISyntaxException;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import model.GovernanceTransaction;
import model.GovernedOrganization;
import model.Organization;
import model.OrganizationKey;
import model.OrganizationStatus;
import model.OrganizationType;
import model.PeerRegistration;

public record GovernanceRegistry(
        Map<String, GovernedOrganization> organizations,
        Map<String, OrganizationKey> organizationKeys,
        Map<String, PeerRegistration> peers
) {
    private static final String P256_ALGORITHM = "ECDSA_P256_SHA256";

    public GovernanceRegistry {
        organizations = Map.copyOf(organizations == null ? Map.of() : organizations);
        organizationKeys = Map.copyOf(organizationKeys == null ? Map.of() : organizationKeys);
        peers = Map.copyOf(peers == null ? Map.of() : peers);
        for (Map.Entry<String, GovernedOrganization> entry : organizations.entrySet()) {
            String id = entry.getKey();
            GovernedOrganization organization = entry.getValue();
            if (!id.equals(organization.organizationId())) {
                throw new IllegalArgumentException("organization registry key does not match organization ID");
            }
        }
        for (Map.Entry<String, OrganizationKey> entry : organizationKeys.entrySet()) {
            String id = entry.getKey();
            OrganizationKey key = entry.getValue();
            if (!id.equals(key.keyId()) || !organizations.containsKey(key.organizationId())) {
                throw new IllegalArgumentException("organization key registry entry is inconsistent");
            }
        }
        for (Map.Entry<String, PeerRegistration> entry : peers.entrySet()) {
            String id = entry.getKey();
            PeerRegistration peer = entry.getValue();
            if (!id.equals(peer.peerId()) || !organizations.containsKey(peer.organizationId())) {
                throw new IllegalArgumentException("peer registry entry is inconsistent");
            }
        }
    }

    public static GovernanceRegistry empty() {
        return new GovernanceRegistry(Map.of(), Map.of(), Map.of());
    }

    public static GovernanceRegistry from(
            Map<String, Organization> organizations,
            Map<String, OrganizationKey> organizationKeys
    ) {
        Map<String, GovernedOrganization> governed = new HashMap<>();
        if (organizations != null) {
            organizations.forEach((id, organization) -> governed.put(
                    id,
                    new GovernedOrganization(
                            organization.organizationId(),
                            organization.type(),
                            organization.organizationId(),
                            null,
                            organization.active() ? OrganizationStatus.ACTIVE : OrganizationStatus.SUSPENDED)));
        }
        return new GovernanceRegistry(governed, organizationKeys, Map.of());
    }

    public GovernanceRegistry apply(GovernanceTransaction transaction, long inclusionHeight) {
        if (transaction == null || inclusionHeight < 0) {
            throw invalid("INVALID_GOVERNANCE_TRANSACTION", "Transaction and non-negative height are required");
        }
        return switch (transaction.governanceType()) {
            case REGISTER_ORGANIZATION -> registerOrganization(transaction, inclusionHeight);
            case REGISTER_ORGANIZATION_KEY -> registerOrganizationKey(transaction, inclusionHeight);
            case REVOKE_ORGANIZATION_KEY -> revokeOrganizationKey(transaction, inclusionHeight);
            case SET_ORGANIZATION_STATUS -> setOrganizationStatus(transaction);
            case REGISTER_PEER -> registerPeer(transaction);
            case REVOKE_PEER -> revokePeer(transaction);
        };
    }

    public Map<String, Organization> organizationValidationView() {
        Map<String, Organization> view = new HashMap<>();
        organizations.forEach((id, organization) -> view.put(id, organization.validationView()));
        return Map.copyOf(view);
    }

    private GovernanceRegistry registerOrganization(GovernanceTransaction transaction, long height) {
        requireFields(transaction, Set.of(
                "organizationId", "organizationType", "name", "keyId", "algorithm", "publicKey"),
                Set.of("province"));
        String organizationId = value(transaction, "organizationId");
        String keyId = value(transaction, "keyId");
        if (organizations.containsKey(organizationId)) {
            throw invalid("ORGANIZATION_ALREADY_REGISTERED", "Organization ID is already registered");
        }
        if (organizationKeys.containsKey(keyId)) {
            throw invalid("ORGANIZATION_KEY_ALREADY_REGISTERED", "Key ID is already registered");
        }
        OrganizationType type = parseEnum(
                OrganizationType.class, value(transaction, "organizationType"), "organizationType");
        String province = transaction.data().get("province");
        if (province != null && province.isBlank()) {
            throw invalid("INVALID_GOVERNANCE_DATA", "province must be omitted or non-blank");
        }
        requireMaxLength(organizationId, 100, "organizationId");
        requireMaxLength(value(transaction, "name"), 200, "name");
        if (province != null) {
            requireMaxLength(province, 100, "province");
        }
        requireMaxLength(keyId, 100, "keyId");
        GovernedOrganization organization = new GovernedOrganization(
                organizationId,
                type,
                value(transaction, "name"),
                province,
                OrganizationStatus.ACTIVE);
        OrganizationKey key = registeredKey(transaction, organizationId, height);
        Map<String, GovernedOrganization> updatedOrganizations = new HashMap<>(organizations);
        Map<String, OrganizationKey> updatedKeys = new HashMap<>(organizationKeys);
        updatedOrganizations.put(organizationId, organization);
        updatedKeys.put(keyId, key);
        return new GovernanceRegistry(updatedOrganizations, updatedKeys, peers);
    }

    private GovernanceRegistry registerOrganizationKey(GovernanceTransaction transaction, long height) {
        requireFields(transaction, Set.of("organizationId", "keyId", "algorithm", "publicKey"), Set.of());
        String organizationId = value(transaction, "organizationId");
        if (!organizations.containsKey(organizationId)) {
            throw invalid("UNKNOWN_ORGANIZATION", "Organization does not exist");
        }
        String keyId = value(transaction, "keyId");
        requireMaxLength(organizationId, 100, "organizationId");
        requireMaxLength(keyId, 100, "keyId");
        if (organizationKeys.containsKey(keyId)) {
            throw invalid("ORGANIZATION_KEY_ALREADY_REGISTERED", "Key ID is already registered");
        }
        OrganizationKey key = registeredKey(transaction, organizationId, height);
        Map<String, OrganizationKey> updated = new HashMap<>(organizationKeys);
        updated.put(keyId, key);
        return new GovernanceRegistry(organizations, updated, peers);
    }

    private GovernanceRegistry revokeOrganizationKey(GovernanceTransaction transaction, long height) {
        requireFields(transaction, Set.of("keyId"), Set.of());
        String keyId = value(transaction, "keyId");
        OrganizationKey key = organizationKeys.get(keyId);
        if (key == null) {
            throw invalid("UNKNOWN_ORGANIZATION_KEY", "Organization key does not exist");
        }
        if (key.revokedAtHeight() != null) {
            throw invalid("ORGANIZATION_KEY_ALREADY_REVOKED", "Organization key was already revoked");
        }
        Map<String, OrganizationKey> updated = new HashMap<>(organizationKeys);
        updated.put(keyId, new OrganizationKey(
                key.keyId(), key.organizationId(), key.publicKey(), key.validFromHeight(), height));
        return new GovernanceRegistry(organizations, updated, peers);
    }

    private GovernanceRegistry setOrganizationStatus(GovernanceTransaction transaction) {
        requireFields(transaction, Set.of("organizationId", "status"), Set.of());
        String organizationId = value(transaction, "organizationId");
        requireMaxLength(organizationId, 100, "organizationId");
        GovernedOrganization organization = organizations.get(organizationId);
        if (organization == null) {
            throw invalid("UNKNOWN_ORGANIZATION", "Organization does not exist");
        }
        OrganizationStatus status = parseEnum(
                OrganizationStatus.class, value(transaction, "status"), "status");
        if (organization.status() == OrganizationStatus.REVOKED && status != OrganizationStatus.REVOKED) {
            throw invalid("REVOKED_ORGANIZATION_TERMINAL", "A revoked organization cannot be reactivated");
        }
        if (organization.status() == status) {
            throw invalid("ORGANIZATION_STATUS_UNCHANGED", "Organization already has the requested status");
        }
        Map<String, GovernedOrganization> updated = new HashMap<>(organizations);
        updated.put(organizationId, new GovernedOrganization(
                organization.organizationId(),
                organization.type(),
                organization.name(),
                organization.province(),
                status));
        return new GovernanceRegistry(updated, organizationKeys, peers);
    }

    private GovernanceRegistry registerPeer(GovernanceTransaction transaction) {
        requireFields(transaction,
                Set.of("peerId", "organizationId", "endpoint", "tlsCertificateFingerprint"), Set.of());
        String peerId = value(transaction, "peerId");
        requireMaxLength(peerId, 100, "peerId");
        if (peers.containsKey(peerId)) {
            throw invalid("PEER_ALREADY_REGISTERED", "Peer ID is already registered");
        }
        String organizationId = value(transaction, "organizationId");
        requireMaxLength(organizationId, 100, "organizationId");
        GovernedOrganization organization = organizations.get(organizationId);
        if (organization == null || organization.status() != OrganizationStatus.ACTIVE) {
            throw invalid("INACTIVE_ORGANIZATION", "Peer organization must be active");
        }
        String endpoint = value(transaction, "endpoint");
        requireMaxLength(endpoint, 500, "endpoint");
        validateEndpoint(endpoint);
        String fingerprint = value(transaction, "tlsCertificateFingerprint");
        if (!fingerprint.matches("[0-9a-f]{64}")) {
            throw invalid("INVALID_PEER_FINGERPRINT", "Peer certificate fingerprint must be lowercase SHA-256 hex");
        }
        Map<String, PeerRegistration> updated = new HashMap<>(peers);
        updated.put(peerId, new PeerRegistration(
                peerId, organizationId, endpoint, fingerprint, true));
        return new GovernanceRegistry(organizations, organizationKeys, updated);
    }

    private GovernanceRegistry revokePeer(GovernanceTransaction transaction) {
        requireFields(transaction, Set.of("peerId"), Set.of());
        String peerId = value(transaction, "peerId");
        requireMaxLength(peerId, 100, "peerId");
        PeerRegistration peer = peers.get(peerId);
        if (peer == null) {
            throw invalid("UNKNOWN_PEER", "Peer does not exist");
        }
        if (!peer.active()) {
            throw invalid("PEER_ALREADY_REVOKED", "Peer was already revoked");
        }
        Map<String, PeerRegistration> updated = new HashMap<>(peers);
        updated.put(peerId, new PeerRegistration(
                peer.peerId(),
                peer.organizationId(),
                peer.endpoint(),
                peer.tlsCertificateFingerprint(),
                false));
        return new GovernanceRegistry(organizations, organizationKeys, updated);
    }

    private OrganizationKey registeredKey(
            GovernanceTransaction transaction,
            String organizationId,
            long height
    ) {
        if (!P256_ALGORITHM.equals(value(transaction, "algorithm"))) {
            throw invalid("UNSUPPORTED_KEY_ALGORITHM", "Only ECDSA_P256_SHA256 keys are supported");
        }
        String publicKey = value(transaction, "publicKey");
        try {
            SignatureUtil.validateP256PublicKey(java.util.Base64.getDecoder().decode(publicKey));
        } catch (IllegalArgumentException | GeneralSecurityException exception) {
            throw new GovernanceValidationException(
                    "INVALID_ORGANIZATION_PUBLIC_KEY", "Organization key must be a valid P-256 SPKI public key",
                    exception);
        }
        return new OrganizationKey(value(transaction, "keyId"), organizationId, publicKey, height, null);
    }

    private void validateEndpoint(String endpoint) {
        try {
            URI uri = new URI(endpoint);
            if (!"https".equalsIgnoreCase(uri.getScheme())
                    || uri.getHost() == null
                    || uri.getUserInfo() != null
                    || uri.getFragment() != null
                    || uri.getQuery() != null
                    || uri.getPort() == 0
                    || uri.getPort() > 65_535) {
                throw invalid("INVALID_PEER_ENDPOINT", "Peer endpoint must be an HTTPS URL without user info");
            }
        } catch (URISyntaxException exception) {
            throw new GovernanceValidationException("INVALID_PEER_ENDPOINT", "Peer endpoint is malformed", exception);
        }
    }

    private void requireFields(
            GovernanceTransaction transaction,
            Set<String> required,
            Set<String> optional
    ) {
        if (!transaction.data().keySet().containsAll(required)) {
            throw invalid("MISSING_GOVERNANCE_DATA", "Governance transaction is missing required fields");
        }
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        if (!allowed.containsAll(transaction.data().keySet())) {
            throw invalid("UNSUPPORTED_GOVERNANCE_DATA", "Governance transaction contains unsupported fields");
        }
    }

    private String value(GovernanceTransaction transaction, String key) {
        String value = transaction.data().get(key);
        if (value == null || value.isBlank()) {
            throw invalid("MISSING_GOVERNANCE_DATA", "Governance field is missing or blank: " + key);
        }
        return value;
    }

    private <T extends Enum<T>> T parseEnum(Class<T> enumType, String value, String field) {
        try {
            return Enum.valueOf(enumType, value);
        } catch (IllegalArgumentException exception) {
            throw new GovernanceValidationException(
                    "INVALID_GOVERNANCE_DATA", "Invalid governance value for " + field, exception);
        }
    }

    private GovernanceValidationException invalid(String code, String message) {
        return new GovernanceValidationException(code, message);
    }

    private void requireMaxLength(String value, int maximum, String field) {
        if (value.length() > maximum) {
            throw invalid("GOVERNANCE_FIELD_TOO_LONG", field + " exceeds " + maximum + " characters");
        }
    }
}
