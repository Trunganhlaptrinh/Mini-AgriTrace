package bootstrap;

import java.util.Arrays;

/** Validates the local node-to-account mapping and clears retrieved credential material. */
public final class LocalAdminCredentialFlow {
    private LocalAdminCredentialFlow() { }

    public static String targetForUsername(String username) {
        return switch (username == null ? "" : username) {
            case "admin-a" -> "AgriTrace/Local3Node/admin-a";
            case "admin-b" -> "AgriTrace/Local3Node/admin-b";
            case "admin-c" -> "AgriTrace/Local3Node/admin-c";
            default -> throw new IllegalArgumentException("Unsupported local ADMIN account");
        };
    }

    public static void initialize(ConsortiumBootstrapService service, String manifest,
                                  String username, String target, LocalAdminCredentialStore store) {
        if (!targetForUsername(username).equals(target))
            throw new IllegalArgumentException("Credential target does not match the local ADMIN account");
        char[] password = store.read(target);
        if (password == null || password.length == 0)
            throw new IllegalStateException("Required local ADMIN credential is not available");
        try {
            service.initialize(manifest, username, password);
        } finally {
            Arrays.fill(password, '\0');
        }
    }
}
