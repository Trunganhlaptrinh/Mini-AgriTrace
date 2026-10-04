package dal;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDAOTest {
    @Test
    void updatesPasswordOnlyWhenOldHashAndAccountActivationStillMatch() {
        FakeJdbc jdbc = new FakeJdbc(1);

        boolean changed = dao(jdbc).changePassword(7, "old-hash", "new-hash");

        assertTrue(changed);
        assertTrue(jdbc.sql.contains("password_hash = ?"));
        assertTrue(jdbc.sql.contains("user_id = ?"));
        assertTrue(jdbc.sql.contains("password_hash = ?"));
        assertTrue(jdbc.sql.contains("is_active = TRUE"));
        assertTrue(jdbc.sql.contains("organization_canonical = TRUE"));
        assertEquals(Map.of(1, "new-hash", 2, 7L, 3, "old-hash"), jdbc.parameters);
    }

    @Test
    void reportsConflictWhenConditionalPasswordUpdateChangesNoRows() {
        FakeJdbc jdbc = new FakeJdbc(0);

        assertFalse(dao(jdbc).changePassword(7, "old-hash", "new-hash"));
    }

    private UserDAO dao(FakeJdbc jdbc) {
        return new UserDAO(jdbc::connection);
    }

    private static final class FakeJdbc {
        private final int updatedRows;
        private final Map<Integer, Object> parameters = new HashMap<>();
        private String sql;

        private FakeJdbc(int updatedRows) {
            this.updatedRows = updatedRows;
        }

        private Connection connection() {
            return proxy(Connection.class, (proxy, method, args) -> switch (method.getName()) {
                case "prepareStatement" -> {
                    sql = (String) args[0];
                    yield statement();
                }
                case "close" -> null;
                case "toString" -> "FakeConnection";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private PreparedStatement statement() {
            return proxy(PreparedStatement.class, (proxy, method, args) -> switch (method.getName()) {
                case "setString", "setLong" -> {
                    parameters.put((Integer) args[0], args[1]);
                    yield null;
                }
                case "executeUpdate" -> updatedRows;
                case "close" -> null;
                case "toString" -> "FakeStatement";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        }

        private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
            return type.cast(Proxy.newProxyInstance(
                    type.getClassLoader(), new Class<?>[]{type}, handler));
        }
    }
}
