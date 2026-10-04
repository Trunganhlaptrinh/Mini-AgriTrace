package model;

public record Organization(
        String organizationId,
        OrganizationType type,
        boolean active
) {
    public Organization {
        if (organizationId == null || organizationId.isBlank()) {
            throw new IllegalArgumentException("organizationId must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
    }
}
