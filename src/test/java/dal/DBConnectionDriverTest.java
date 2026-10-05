package dal;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import util.DBConnection;

class DBConnectionDriverTest {
    @Test
    void mysqlDriverIsVisibleToApplicationClassLoader() {
        assertDoesNotThrow(() -> {
            Class.forName("com.mysql.cj.jdbc.Driver", true, DBConnection.class.getClassLoader());
            DriverManager.getDriver("jdbc:mysql://127.0.0.1:3307/agritrace");
        });
    }
}
