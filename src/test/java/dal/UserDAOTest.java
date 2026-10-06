package dal;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UserDAOTest {
    @Test
    void checksActiveAccountAndCanonicalOrganizationAvailabilityForExistingSessions() {
        Map<String, Boolean> columns = Map.of("is_active", true, "organization_canonical", false);
        String[] sql = new String[1];
        UserDAO userDAO = new UserDAO(() -> proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> {
                sql[0] = (String) args[0];
                yield proxy(PreparedStatement.class, (statementMethod, statementArgs) -> switch (statementMethod) {
                    case "setLong", "close" -> null;
                    case "executeQuery" -> {
                        boolean[] read = {false};
                        yield proxy(ResultSet.class, (resultMethod, resultArgs) -> switch (resultMethod) {
                            case "next" -> !read[0] && (read[0] = true);
                            case "getBoolean" -> columns.get(resultArgs[0]);
                            case "close" -> null;
                            default -> throw new UnsupportedOperationException(resultMethod);
                        });
                    }
                    default -> throw new UnsupportedOperationException(statementMethod);
                });
            }
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        }));

        assertFalse(userDAO.isAccountAuthorized(12));
        assertTrue(sql[0].contains("is_active"));
        assertTrue(sql[0].contains("organization_canonical"));
    }

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

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
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
