package bootstrap;

import dal.ConnectionProvider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/** Read-only gate ensuring bootstrap is aimed at an empty, initialized agritrace schema. */
public final class BootstrapDatabaseGuard {
    private static final List<String> TABLES = List.of("network_config", "blockchain_transactions",
            "blockchain_blocks", "block_transactions", "transaction_pool", "node_transaction_status",
            "organizations", "organization_keys", "authorized_peers", "users", "shipment_proposals",
            "shipment_proposal_signatures", "batches", "batch_events");
    private final ConnectionProvider connections;

    public BootstrapDatabaseGuard(ConnectionProvider connections) {
        this.connections = Objects.requireNonNull(connections, "connections");
    }

    public void requireEmptyInitializedDatabase() {
        try (Connection c = connections.getConnection()) {
            if (!"agritrace".equalsIgnoreCase(c.getCatalog()))
                throw new IllegalStateException("Bootstrap target database must be named agritrace");
            for (String table : TABLES) {
                try (PreparedStatement q = c.prepareStatement("SELECT COUNT(*) FROM " + table);
                     ResultSet r = q.executeQuery()) {
                    if (!r.next()) throw new IllegalStateException("Could not inspect bootstrap target");
                    long count = r.getLong(1);
                    if (count != 0) throw new IllegalStateException("Bootstrap target contains pre-existing data in " + table);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap target schema is missing or could not be inspected", e);
        }
    }
}
