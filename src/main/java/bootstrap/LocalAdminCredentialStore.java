package bootstrap;

/** Secure local credential lookup used only by the offline bootstrap CLI. */
@FunctionalInterface
public interface LocalAdminCredentialStore {
    /** Returns a caller-owned secret array, or null when the target does not exist. */
    char[] read(String target);
}
