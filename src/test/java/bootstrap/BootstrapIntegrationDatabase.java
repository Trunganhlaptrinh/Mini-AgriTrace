package bootstrap;

import dal.ConnectionProvider;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Properties;

/** Connects only to the explicitly configured and marked bootstrap integration instance. */
final class BootstrapIntegrationDatabase {
    static final String CATALOG = "agritrace_test";
    private static final String CONFIRM = "USE_ONLY_DEDICATED_AGRITRACE_BOOTSTRAP_IT";
    private static final String MARKER = "agritrace-bootstrap-it-only-v1";
    private final String url;
    private final String username;
    private final String password;

    private BootstrapIntegrationDatabase(String url, String username, String password) {
        this.url = url;
        this.username = username;
        this.password = password;
    }

    static BootstrapIntegrationDatabase requireConfigured() throws SQLException {
        if (!"true".equalsIgnoreCase(System.getenv("AGRITRACE_BOOTSTRAP_IT_ENABLED")))
            throw new IllegalStateException("Set AGRITRACE_BOOTSTRAP_IT_ENABLED=true to opt in");
        if (!CONFIRM.equals(System.getenv("AGRITRACE_BOOTSTRAP_IT_CONFIRM")))
            throw new IllegalStateException("Bootstrap integration DB confirmation is missing");
        String url = required("AGRITRACE_BOOTSTRAP_IT_JDBC_URL");
        if (!url.matches("(?i)^jdbc:mysql://127\\.0\\.0\\.1:3306/agritrace_test(?:\\?.*)?$"))
            throw new IllegalStateException("Bootstrap integration JDBC URL must target 127.0.0.1:3306/agritrace_test");
        if (url.matches("(?i).*([?&](user|password|pwd)=).*"))
            throw new IllegalStateException("Bootstrap integration JDBC URL must not embed credentials");
        String username = required("AGRITRACE_BOOTSTRAP_IT_USERNAME");
        String password = required("AGRITRACE_BOOTSTRAP_IT_PASSWORD");
        String normalUrl = System.getenv("AGRITRACE_DB_URL");
        if (normalUrl == null || normalUrl.isBlank()) normalUrl = System.getProperty("agritrace.db.url");
        if (normalUrl != null && normalUrl.equals(url))
            throw new IllegalStateException("Bootstrap integration URL must differ from the normal application DB URL");
        BootstrapIntegrationDatabase database = new BootstrapIntegrationDatabase(url, username, password);
        database.requireIsolationMarker();
        return database;
    }

    ConnectionProvider connections() { return this::connect; }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectionTimeZone", "UTC");
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        return DriverManager.getConnection(url, properties);
    }

    private void requireIsolationMarker() throws SQLException {
        try (Connection c = connect()) {
            if (!CATALOG.equalsIgnoreCase(c.getCatalog()))
                throw new IllegalStateException("Bootstrap integration DB catalog must be agritrace_test");
            try (PreparedStatement q = c.prepareStatement(
                    "SELECT marker FROM _agritrace_bootstrap_it_guard WHERE guard_id=1");
                 ResultSet r = q.executeQuery()) {
                if (!r.next() || !MARKER.equals(r.getString(1)) || r.next())
                    throw new IllegalStateException("Bootstrap integration DB isolation marker is invalid");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Bootstrap integration DB is unavailable or lacks its dedicated isolation marker", e);
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing required setting: " + key);
        return value;
    }
}
