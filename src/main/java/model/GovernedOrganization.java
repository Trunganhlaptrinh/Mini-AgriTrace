package model;

public record GovernedOrganization(
        String organizationId,
        OrganizationType type,
        String name,
        String province,
        OrganizationStatus status
) {
    public GovernedOrganization {
        requireText(organizationId, "organizationId");
        if (type == null || status == null) {
            throw new IllegalArgumentException("type and status must not be null");
        }
        requireText(name, "name");
        if (province != null && province.isBlank()) {
            throw new IllegalArgumentException("province must be null or non-blank");
        }
    }

    public Organization validationView() {
        return new Organization(organizationId, type, status == OrganizationStatus.ACTIVE);
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
