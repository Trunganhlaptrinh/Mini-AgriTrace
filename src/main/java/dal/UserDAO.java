package dal;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.Optional;
import util.DBConnection;

public final class UserDAO {
    private static final String FIND_BY_USERNAME = """
            SELECT user_id, username, password_hash, role, organization_id,
                   is_active, organization_canonical
            FROM users
            WHERE username = ?
            """;
    private static final String CHANGE_PASSWORD = """
            UPDATE users
            SET password_hash = ?
            WHERE user_id = ?
              AND password_hash = ?
              AND is_active = TRUE
              AND organization_canonical = TRUE
            """;
    private static final String FIND_CANONICAL_ORGANIZATION = """
            SELECT organization_type, status
            FROM organizations
            WHERE organization_id = ?
            FOR UPDATE
            """;
    private static final String INSERT_USER = """
            INSERT INTO users (username, password_hash, role, organization_id)
            VALUES (?, ?, ?, ?)
            """;
    private static final String UPDATE_ACTIVE = """
            UPDATE users
            SET is_active = ?
            WHERE user_id = ?
            """;
    private static final String USER_EXISTS = """
            SELECT user_id
            FROM users
            WHERE user_id = ?
            """;

    private final ConnectionProvider connectionProvider;

    public UserDAO() {
        this(DBConnection::getConnection);
    }

    public UserDAO(ConnectionProvider connectionProvider) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
    }

    public Optional<UserCredential> findByUsername(String username) {
        if (username == null || username.isBlank() || username.length() > 100) {
            throw new IllegalArgumentException("username must contain 1 to 100 characters");
        }
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_BY_USERNAME)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                try {
                    return Optional.of(new UserCredential(
                            result.getLong("user_id"),
                            result.getString("username"),
                            result.getString("password_hash"),
                            result.getString("role"),
                            result.getString("organization_id"),
                            result.getBoolean("is_active"),
                            result.getBoolean("organization_canonical")));
                } catch (IllegalArgumentException exception) {
                    throw new PersistenceException("Stored local account is inconsistent", exception);
                }
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not read local account", exception);
        }
    }

    public boolean changePassword(long userId, String expectedPasswordHash, String newPasswordHash) {
        if (userId <= 0 || expectedPasswordHash == null || expectedPasswordHash.isBlank()
                || newPasswordHash == null || newPasswordHash.isBlank()) {
            throw new IllegalArgumentException("valid user and password hashes are required");
        }
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(CHANGE_PASSWORD)) {
            statement.setString(1, newPasswordHash);
            statement.setLong(2, userId);
            statement.setString(3, expectedPasswordHash);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new PersistenceException("Could not update local account password", exception);
        }
    }

    public long createUser(String username, String passwordHash, String role, String organizationId) {
        validateNewUser(username, passwordHash, role, organizationId);
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                if (!"ADMIN".equals(role)) {
                    String organizationType = lockActiveOrganization(connection, organizationId);
                    if (!role.equals(organizationType)) {
                        throw new IllegalArgumentException(
                                "User role must match an active canonical organization type");
                    }
                }
                long userId;
                try (PreparedStatement statement = connection.prepareStatement(
                        INSERT_USER, Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, username);
                    statement.setString(2, passwordHash);
                    statement.setString(3, role);
                    statement.setString(4, organizationId);
                    statement.executeUpdate();
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("MySQL did not return the generated user ID");
                        }
                        userId = keys.getLong(1);
                    }
                }
                connection.commit();
                return userId;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not create local account", exception);
        }
    }

    public boolean setActive(long userId, boolean active) {
        if (userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        try (Connection connection = connectionProvider.getConnection()) {
            try (PreparedStatement update = connection.prepareStatement(UPDATE_ACTIVE)) {
                update.setBoolean(1, active);
                update.setLong(2, userId);
                if (update.executeUpdate() == 1) {
                    return true;
                }
            }
            try (PreparedStatement query = connection.prepareStatement(USER_EXISTS)) {
                query.setLong(1, userId);
                try (ResultSet result = query.executeQuery()) {
                    return result.next();
                }
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not update local account status", exception);
        }
    }

    private String lockActiveOrganization(Connection connection, String organizationId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_CANONICAL_ORGANIZATION)) {
            statement.setString(1, organizationId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next() || !"ACTIVE".equals(result.getString("status"))) {
                    throw new IllegalArgumentException("Organization is not active on the canonical chain");
                }
                return result.getString("organization_type");
            }
        }
    }

    private void validateNewUser(String username, String passwordHash, String role, String organizationId) {
        if (username == null || username.isBlank() || username.length() > 100
                || passwordHash == null || passwordHash.isBlank() || passwordHash.length() > 255
                || role == null || !java.util.Set.of(
                        "ADMIN", "FARMER", "CARRIER", "WAREHOUSE", "RETAILER").contains(role)
                || ("ADMIN".equals(role) != (organizationId == null))
                || (organizationId != null && (organizationId.isBlank() || organizationId.length() > 100))) {
            throw new IllegalArgumentException("New user fields are invalid or inconsistent");
        }
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    public record UserCredential(
            long userId,
            String username,
            String passwordHash,
            String role,
            String organizationId,
            boolean active,
            boolean organizationCanonical
    ) {
        public UserCredential {
            if (userId <= 0 || username == null || username.isBlank()
                    || passwordHash == null || passwordHash.isBlank()
                    || role == null || role.isBlank()) {
                throw new IllegalArgumentException("stored account credentials are incomplete");
            }
            if ("ADMIN".equals(role) != (organizationId == null)) {
                throw new IllegalArgumentException("stored account organization does not match its role");
            }
        }
    }
}
