package service;

import dal.PersistenceException;
import dal.UserDAO;
import java.util.Objects;
import java.util.Optional;
import security.PasswordHasher;

public final class AuthenticationService {
    private static final String DUMMY_PASSWORD_HASH = createDummyPasswordHash();
    private static final int MIN_NEW_PASSWORD_CHARS = 12;
    private final UserAccountRepository userAccountRepository;
    private final PasswordHasher passwordHasher;

    public AuthenticationService(UserDAO userDAO, PasswordHasher passwordHasher) {
        this(userRepository(Objects.requireNonNull(userDAO, "userDAO")), passwordHasher);
    }

    private static UserAccountRepository userRepository(UserDAO userDAO) {
        return new UserAccountRepository() {
            @Override
            public Optional<UserDAO.UserCredential> findByUsername(String username) {
                return userDAO.findByUsername(username);
            }

            @Override
            public boolean changePassword(long userId, String expectedPasswordHash, String newPasswordHash) {
                return userDAO.changePassword(userId, expectedPasswordHash, newPasswordHash);
            }
        };
    }

    AuthenticationService(UserAccountRepository userAccountRepository, PasswordHasher passwordHasher) {
        this.userAccountRepository = Objects.requireNonNull(userAccountRepository, "userAccountRepository");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
    }

    public AuthenticatedAccount authenticate(String username, char[] password) {
        if (username == null || username.isBlank() || username.length() > 100
                || password == null || password.length == 0) {
            throw new AuthenticationException(
                    "INVALID_REQUEST", "Username and password are required", 400);
        }
        Optional<UserDAO.UserCredential> result = userAccountRepository.findByUsername(username);
        if (result.isEmpty()) {
            passwordHasher.verify(password, DUMMY_PASSWORD_HASH);
            throw new AuthenticationException(
                    "INVALID_CREDENTIALS", "Invalid username or password", 401);
        }
        if (!passwordHasher.verify(password, result.get().passwordHash())) {
            throw new AuthenticationException(
                    "INVALID_CREDENTIALS", "Invalid username or password", 401);
        }
        UserDAO.UserCredential user = result.get();
        if (!user.active() || !user.organizationCanonical()) {
            throw new AuthenticationException(
                    "INVALID_CREDENTIALS", "Invalid username or password", 401);
        }
        return new AuthenticatedAccount(
                user.userId(), user.username(), user.role(), user.organizationId());
    }

    public void changePassword(
            long userId,
            String username,
            char[] currentPassword,
            char[] newPassword
    ) {
        if (userId <= 0 || username == null || username.isBlank()
                || currentPassword == null || currentPassword.length == 0
                || newPassword == null || newPassword.length < MIN_NEW_PASSWORD_CHARS
                || newPassword.length > 1024) {
            throw new AuthenticationException(
                    "INVALID_REQUEST",
                    "Current password is required and new password must contain 12 to 1024 characters",
                    400);
        }

        Optional<UserDAO.UserCredential> result = userAccountRepository.findByUsername(username);
        if (result.isEmpty() || result.get().userId() != userId) {
            throw new AuthenticationException(
                    "ACCOUNT_INACTIVE", "Account is no longer available", 403);
        }
        UserDAO.UserCredential user = result.get();
        if (!passwordHasher.verify(currentPassword, user.passwordHash())) {
            throw new AuthenticationException(
                    "INVALID_CURRENT_PASSWORD", "Current password is incorrect", 401);
        }
        if (!user.active() || !user.organizationCanonical()) {
            throw new AuthenticationException(
                    "ACCOUNT_INACTIVE", "Account is inactive", 403);
        }

        String newHash = passwordHasher.hash(newPassword);
        if (!userAccountRepository.changePassword(userId, user.passwordHash(), newHash)) {
            throw new AuthenticationException(
                    "PASSWORD_CHANGE_CONFLICT",
                    "Account changed while the password was being updated; sign in again",
                    409);
        }
    }

    private static String createDummyPasswordHash() {
        char[] dummyPassword = "AgriTrace-invalid-account-check".toCharArray();
        try {
            return new PasswordHasher().hash(dummyPassword);
        } finally {
            java.util.Arrays.fill(dummyPassword, '\0');
        }
    }

    @FunctionalInterface
    interface UserAccountRepository {
        Optional<UserDAO.UserCredential> findByUsername(String username) throws PersistenceException;

        default boolean changePassword(long userId, String expectedPasswordHash, String newPasswordHash)
                throws PersistenceException {
            return false;
        }
    }
}
