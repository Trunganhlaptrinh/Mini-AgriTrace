package util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Properties;

public final class DBConnection {
    private DBConnection() {
    }

    public static Connection getConnection() throws SQLException {
        String url = configuration("agritrace.db.url", "AGRITRACE_DB_URL");
        String username = configuration("agritrace.db.username", "AGRITRACE_DB_USERNAME");
        String password = configuration("agritrace.db.password", "AGRITRACE_DB_PASSWORD");
        if (!url.startsWith("jdbc:mysql:")) {
            throw new SQLException("AgriTrace database URL must use the MySQL JDBC driver");
        }

        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("connectionTimeZone", "UTC");
        properties.setProperty("forceConnectionTimeZoneToSession", "true");
        return DriverManager.getConnection(url, properties);
    }

    private static String configuration(String systemProperty, String environmentVariable)
            throws SQLException {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(environmentVariable);
        }
        if (value == null || value.isBlank()) {
            throw new SQLException("Missing required database configuration: " + systemProperty
                    + " or " + environmentVariable);
        }
        return value;
    }
}
