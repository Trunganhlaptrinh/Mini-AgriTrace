package service;

import dal.UserDAO;
import java.util.Optional;
import security.PasswordHasher;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthenticationServiceTest {
    private static final PasswordHasher PASSWORD_HASHER = new PasswordHasher();
    private static String activePasswordHash;

    @BeforeAll
    static void setUpPasswordHash() {
        activePasswordHash = PASSWORD_HASHER.hash("correct-password".toCharArray());
    }

    @Test
    void authenticatesActiveAccountAndReturnsOnlyPublicAccountFields() {
        AuthenticationService service = service(account(true, true, activePasswordHash));

        AuthenticatedAccount result = service.authenticate("farmer.one", "correct-password".toCharArray());

        assertEquals(new AuthenticatedAccount(12, "farmer.one", "FARMER", "farm-1"), result);
    }

    @Test
    void rejectsUnknownUsersAndIncorrectPasswordsWithTheSamePublicError() {
        AuthenticationService unknownUser = service(Optional.empty());
        AuthenticationException unknown = assertThrows(
                AuthenticationException.class,
                () -> unknownUser.authenticate("missing", "incorrect-password".toCharArray()));

        AuthenticationService wrongPassword = service(account(true, true, activePasswordHash));
        AuthenticationException wrong = assertThrows(
                AuthenticationException.class,
                () -> wrongPassword.authenticate("farmer.one", "incorrect-password".toCharArray()));

        assertEquals(401, unknown.getHttpStatus());
        assertEquals(unknown.getCode(), wrong.getCode());
        assertEquals(unknown.getMessage(), wrong.getMessage());
    }

    @Test
    void refusesInactiveAndCanonicallyUnavailableAccountsWithTheGenericLoginFailure() {
        AuthenticationService locallyInactive = service(account(false, true, activePasswordHash));
        AuthenticationException inactive = assertThrows(
                AuthenticationException.class,
                () -> locallyInactive.authenticate("farmer.one", "correct-password".toCharArray()));
        assertEquals(401, inactive.getHttpStatus());
        assertEquals("INVALID_CREDENTIALS", inactive.getCode());
        assertEquals("Invalid username or password", inactive.getMessage());

        AuthenticationService organizationUnavailable = service(account(true, false, activePasswordHash));
        AuthenticationException unavailable = assertThrows(
                AuthenticationException.class,
                () -> organizationUnavailable.authenticate("farmer.one", "correct-password".toCharArray()));
        assertEquals(401, unavailable.getHttpStatus());
        assertEquals(inactive.getCode(), unavailable.getCode());
        assertEquals(inactive.getMessage(), unavailable.getMessage());
    }

    @Test
    void rejectsInvalidCredentialsBeforeDatabaseLookup() {
        AuthenticationService service = new AuthenticationService(
                username -> {
                    throw new AssertionError("Invalid request should not query the account store");
                },
                PASSWORD_HASHER);

        AuthenticationException exception = assertThrows(
                AuthenticationException.class,
                () -> service.authenticate(" ", "password".toCharArray()));

        assertEquals(400, exception.getHttpStatus());
        assertEquals("INVALID_REQUEST", exception.getCode());
    }

    @Test
    void changesPasswordOnlyAfterVerifyingCurrentPasswordAndAtomicallyReplacesHash() {
        MutableAccountRepository repository = new MutableAccountRepository(
                account(true, true, activePasswordHash).orElseThrow());
        AuthenticationService service = new AuthenticationService(repository, PASSWORD_HASHER);
        char[] newPassword = "a-new-password-with-length".toCharArray();

        service.changePassword(
                12, "farmer.one", "correct-password".toCharArray(), newPassword);

        assertTrue(repository.passwordChanged);
        assertFalse(repository.credential.passwordHash().equals(activePasswordHash));
        assertTrue(PASSWORD_HASHER.verify(newPassword, repository.credential.passwordHash()));
        assertFalse(PASSWORD_HASHER.verify(
                "correct-password".toCharArray(), repository.credential.passwordHash()));
    }

    @Test
    void rejectsWrongCurrentPasswordAndWeakNewPasswordWithoutUpdatingHash() {
        MutableAccountRepository repository = new MutableAccountRepository(
                account(true, true, activePasswordHash).orElseThrow());
        AuthenticationService service = new AuthenticationService(repository, PASSWORD_HASHER);

        AuthenticationException wrongCurrent = assertThrows(
                AuthenticationException.class,
                () -> service.changePassword(
                        12, "farmer.one", "wrong-current".toCharArray(),
                        "a-new-password-with-length".toCharArray()));
        assertEquals("INVALID_CURRENT_PASSWORD", wrongCurrent.getCode());
        assertFalse(repository.passwordChanged);

        AuthenticationException weakNew = assertThrows(
                AuthenticationException.class,
                () -> service.changePassword(
                        12, "farmer.one", "correct-password".toCharArray(), "short".toCharArray()));
        assertEquals("INVALID_REQUEST", weakNew.getCode());
        assertFalse(repository.passwordChanged);
    }

    @Test
    void rejectsConcurrentPasswordChangesAndInactiveAccounts() {
        MutableAccountRepository repository = new MutableAccountRepository(
                account(true, true, activePasswordHash).orElseThrow());
        repository.updateSucceeds = false;
        AuthenticationService service = new AuthenticationService(repository, PASSWORD_HASHER);

        AuthenticationException conflict = assertThrows(
                AuthenticationException.class,
                () -> service.changePassword(
                        12, "farmer.one", "correct-password".toCharArray(),
                        "a-new-password-with-length".toCharArray()));
        assertEquals(409, conflict.getHttpStatus());

        AuthenticationService inactive = service(account(false, true, activePasswordHash));
        AuthenticationException inactiveException = assertThrows(
                AuthenticationException.class,
                () -> inactive.changePassword(
                        12, "farmer.one", "correct-password".toCharArray(),
                        "a-new-password-with-length".toCharArray()));
        assertEquals(403, inactiveException.getHttpStatus());
    }

    private static AuthenticationService service(Optional<UserDAO.UserCredential> account) {
        return new AuthenticationService(username -> account, PASSWORD_HASHER);
    }

    private static Optional<UserDAO.UserCredential> account(
            boolean active,
            boolean organizationCanonical,
            String passwordHash
    ) {
        return Optional.of(new UserDAO.UserCredential(
                12, "farmer.one", passwordHash, "FARMER", "farm-1", active, organizationCanonical));
    }

    private static final class MutableAccountRepository
            implements AuthenticationService.UserAccountRepository {
        private UserDAO.UserCredential credential;
        private boolean passwordChanged;
        private boolean updateSucceeds = true;

        private MutableAccountRepository(UserDAO.UserCredential credential) {
            this.credential = credential;
        }

        @Override
        public Optional<UserDAO.UserCredential> findByUsername(String username) {
            return credential.username().equals(username) ? Optional.of(credential) : Optional.empty();
        }

        @Override
        public boolean changePassword(long userId, String expectedHash, String newHash) {
            if (!updateSucceeds || credential.userId() != userId
                    || !credential.passwordHash().equals(expectedHash)) {
                return false;
            }
            credential = new UserDAO.UserCredential(
                    credential.userId(),
                    credential.username(),
                    newHash,
                    credential.role(),
                    credential.organizationId(),
                    credential.active(),
                    credential.organizationCanonical());
            passwordChanged = true;
            return true;
        }
    }
}
