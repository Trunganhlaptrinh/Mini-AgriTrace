package bootstrap;

/** Internal progress notification used by the opt-in crash-recovery integration test. */
public record BootstrapCheckpoint(Stage stage, int manifestBlockIndex) {
    public enum Stage { AFTER_NETWORK_CONFIG, AFTER_GENESIS, AFTER_INITIAL_BLOCK, BEFORE_ADMIN, AFTER_ADMIN }
}
