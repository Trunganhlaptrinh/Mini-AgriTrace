package service;

public record AuthenticatedAccount(
        long userId,
        String username,
        String role,
        String organizationId
) {
    public AuthenticatedAccount {
        if (userId <= 0 || username == null || username.isBlank()
                || role == null || role.isBlank()) {
            throw new IllegalArgumentException("authenticated account is incomplete");
        }
        if ("ADMIN".equals(role) != (organizationId == null)) {
            throw new IllegalArgumentException("authenticated account organization does not match its role");
        }
    }
}
