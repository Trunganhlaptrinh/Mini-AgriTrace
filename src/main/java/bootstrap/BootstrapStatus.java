package bootstrap;

/** Machine-readable result of comparing one database with one verified signed manifest. */
public record BootstrapStatus(
        State state,
        String networkId,
        int blocksPresent,
        int blocksExpected,
        int governanceTransactionsPresent,
        int governanceTransactionsExpected,
        String localAdminUsername,
        boolean localPeerVerified,
        String detail
) {
    public enum State {
        UNINITIALIZED,
        RESUMABLE,
        INITIALIZED,
        INCONSISTENT,
        DIFFERENT_NETWORK,
        UNEXPECTED_DATA
    }
}
