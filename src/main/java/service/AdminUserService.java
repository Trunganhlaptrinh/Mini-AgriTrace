package service;

import dal.UserDAO;
import java.util.Objects;
import security.PasswordHasher;

public final class AdminUserService {
    private final UserDAO userDAO;
    private final PasswordHasher passwordHasher;

    public AdminUserService(UserDAO userDAO, PasswordHasher passwordHasher) {
        this.userDAO = Objects.requireNonNull(userDAO, "userDAO");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
    }

    public long createUser(
            AuthenticatedAccount actor,
            String username,
            char[] temporaryPassword,
            String role,
            String organizationId
    ) {
        requireAdministrator(actor);
        if (temporaryPassword == null || temporaryPassword.length < 12
                || temporaryPassword.length > 1024) {
            throw new AuthenticationException(
                    "INVALID_REQUEST", "Temporary password must contain 12 to 1024 characters", 400);
        }
        String passwordHash = passwordHasher.hash(temporaryPassword);
        return userDAO.createUser(username, passwordHash, role, organizationId);
    }

    public void setUserActive(AuthenticatedAccount actor, long userId, boolean active) {
        requireAdministrator(actor);
        if (!userDAO.setActive(userId, active)) {
            throw new AuthenticationException("USER_NOT_FOUND", "User account was not found", 404);
        }
    }

    private void requireAdministrator(AuthenticatedAccount actor) {
        if (actor == null || !"ADMIN".equals(actor.role())) {
            throw new AuthenticationException(
                    "FORBIDDEN", "Administrator access is required", 403);
        }
    }
}
