package security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PasswordHasherTest {
    private final PasswordHasher passwordHasher = new PasswordHasher();

    @Test
    void hashesPasswordsWithIndependentSaltsAndVerifiesTheOriginalPassword() {
        char[] password = "correct horse battery staple".toCharArray();

        String firstHash = passwordHasher.hash(password);
        String secondHash = passwordHasher.hash(password);

        assertNotEquals(firstHash, secondHash);
        assertTrue(passwordHasher.verify(password, firstHash));
        assertTrue(passwordHasher.verify(password, secondHash));
        assertFalse(passwordHasher.verify("incorrect password".toCharArray(), firstHash));
    }

    @Test
    void rejectsMalformedOrUnsafeStoredHashesWithoutDeriving() {
        char[] password = "correct horse battery staple".toCharArray();

        assertFalse(passwordHasher.verify(password, null));
        assertFalse(passwordHasher.verify(password, "unknown$600000$salt$hash"));
        assertFalse(passwordHasher.verify(password, "pbkdf2-sha256$1$salt$hash"));
        assertFalse(passwordHasher.verify(password, "pbkdf2-sha256$2000001$salt$hash"));
        assertFalse(passwordHasher.verify(password, "pbkdf2-sha256$600000$%%%$%%%"));
    }

    @Test
    void rejectsNullAndEmptyPasswords() {
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.hash(null));
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.hash(new char[0]));
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.verify(null, "hash"));
        assertThrows(IllegalArgumentException.class, () -> passwordHasher.verify(new char[0], "hash"));
    }
}
