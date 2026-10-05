package bootstrap;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class LocalAdminCredentialFlowTest {
    @Test
    void mapsOnlyTheThreeLocalAccountsToTheirOwnTargets() {
        assertEquals("AgriTrace/Local3Node/admin-a", LocalAdminCredentialFlow.targetForUsername("admin-a"));
        assertEquals("AgriTrace/Local3Node/admin-b", LocalAdminCredentialFlow.targetForUsername("admin-b"));
        assertEquals("AgriTrace/Local3Node/admin-c", LocalAdminCredentialFlow.targetForUsername("admin-c"));
        assertThrows(IllegalArgumentException.class, () -> LocalAdminCredentialFlow.targetForUsername("other"));
    }

    @Test
    void rejectsCredentialTargetAccountMismatchBeforeReadingSecret() {
        assertThrows(IllegalArgumentException.class, () -> LocalAdminCredentialFlow.initialize(
                null, "manifest", "admin-a", "AgriTrace/Local3Node/admin-b", target -> {
                    fail("Credential store must not be queried for a mismatched target");
                    return null;
                }));
    }

    @Test
    void clearsRetrievedPasswordEvenWhenBootstrapFails() {
        char[] secret = "independent-random-secret".toCharArray();
        assertThrows(NullPointerException.class, () -> LocalAdminCredentialFlow.initialize(
                null, "manifest", "admin-a", "AgriTrace/Local3Node/admin-a", target -> secret));
        for (char value : secret) assertEquals('\0', value);
    }
}
