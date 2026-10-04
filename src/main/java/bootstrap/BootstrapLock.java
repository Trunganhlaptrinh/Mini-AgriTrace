package bootstrap;

import dal.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** MySQL session lock serializing bootstrap writers without schema changes. */
final class BootstrapLock implements AutoCloseable {
    private static final String LOCK_NAME = "agritrace:consortium-bootstrap:v1";
    private final Connection connection;

    private BootstrapLock(Connection connection) { this.connection = connection; }

    static BootstrapLock acquire(ConnectionProvider connections) {
        try {
            Connection c = connections.getConnection();
            try (PreparedStatement q = c.prepareStatement("SELECT GET_LOCK(?, 30)")) {
                q.setString(1, LOCK_NAME);
                try (ResultSet r = q.executeQuery()) {
                    if (!r.next() || r.getInt(1) != 1) {
                        c.close();
                        throw new IllegalStateException("Another bootstrap operation holds the database lock");
                    }
                }
            }
            return new BootstrapLock(c);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not acquire the bootstrap database lock", e);
        }
    }

    @Override public void close() {
        try { connection.close(); }
        catch (SQLException e) { throw new IllegalStateException("Could not release bootstrap database lock", e); }
    }
}
