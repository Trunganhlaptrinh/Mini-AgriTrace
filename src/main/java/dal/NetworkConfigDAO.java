package dal;

import blockchain.SignatureUtil;
import config.NetworkConfiguration;
import java.security.GeneralSecurityException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Objects;
import java.util.TimeZone;
import util.DBConnection;

public final class NetworkConfigDAO {
    private static final String FIND_CONFIGURATION = """
            SELECT network_id, genesis_hash, initial_pow_difficulty,
                   genesis_timestamp, genesis_nonce, genesis_admin_public_key
            FROM network_config
            WHERE id = 1
            """;
    private static final String INSERT_CONFIGURATION = """
            INSERT INTO network_config
                (id, network_id, genesis_hash, initial_pow_difficulty, genesis_timestamp,
                 genesis_nonce, genesis_admin_public_key)
            VALUES (1, ?, ?, ?, ?, ?, ?)
            """;
    private final ConnectionProvider connectionProvider;

    public NetworkConfigDAO() {
        this(DBConnection::getConnection);
    }

    public NetworkConfigDAO(ConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    public NetworkConfiguration loadRequired() {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_CONFIGURATION);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                throw new PersistenceException("Network configuration row with id 1 is missing");
            }
            Timestamp genesisTimestamp = result.getTimestamp(
                    "genesis_timestamp", Calendar.getInstance(TimeZone.getTimeZone("UTC")));
            long nonce = result.getLong("genesis_nonce");
            if (result.wasNull() || genesisTimestamp == null) {
                throw new PersistenceException(
                        "Network genesis timestamp and nonce must be configured before startup");
            }
            try {
                NetworkConfiguration configuration = new NetworkConfiguration(
                        result.getString("network_id"),
                        result.getString("genesis_hash"),
                        result.getInt("initial_pow_difficulty"),
                        genesisTimestamp.toInstant(),
                        nonce,
                        result.getString("genesis_admin_public_key"));
                try {
                    SignatureUtil.validateP256PublicKey(
                            configuration.genesisAdminPublicKeyBytes());
                } catch (GeneralSecurityException | IllegalArgumentException exception) {
                    throw new PersistenceException(
                            "Stored genesis administrator key is not a valid P-256 SPKI key", exception);
                }
                if (result.next()) {
                    throw new PersistenceException("Network configuration must contain exactly one row");
                }
                return configuration;
            } catch (IllegalArgumentException exception) {
                throw new PersistenceException("Stored network configuration is invalid", exception);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load network configuration", exception);
        }
    }

    /** Installs the one network identity row; refuses to overwrite any existing configuration. */
    public void installForBootstrap(NetworkConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement check = connection.prepareStatement(FIND_CONFIGURATION);
                     ResultSet result = check.executeQuery()) {
                    if (result.next()) throw new PersistenceException("Network configuration already exists");
                }
                try (PreparedStatement insert = connection.prepareStatement(INSERT_CONFIGURATION)) {
                    insert.setString(1, configuration.networkId());
                    insert.setString(2, configuration.genesisHash());
                    insert.setInt(3, configuration.initialPowDifficulty());
                    insert.setTimestamp(4, Timestamp.from(configuration.genesisTimestamp()),
                            Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                    insert.setLong(5, configuration.genesisNonce());
                    insert.setString(6, configuration.genesisAdminPublicKey());
                    if (insert.executeUpdate() != 1) throw new PersistenceException("Network configuration was not installed");
                }
                connection.commit();
            } catch (Exception exception) {
                try { connection.rollback(); } catch (SQLException rollback) { exception.addSuppressed(rollback); }
                if (exception instanceof RuntimeException runtime) throw runtime;
                throw new PersistenceException("Could not install network configuration", exception);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not install network configuration", exception);
        }
    }
}
