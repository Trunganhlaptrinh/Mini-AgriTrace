package dal;

import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class CanonicalProjectionDAOTest {
    @Test
    void marksLocalOrganizationAccountsAvailableOnlyWhenCanonicalOrganizationIsActive() throws Exception {
        String[] sql = new String[1];
        Connection connection = proxy(Connection.class, (method, args) -> {
            if ("prepareStatement".equals(method)) {
                sql[0] = (String) args[0];
                return proxy(PreparedStatement.class, (statementMethod, statementArgs) ->
                        "executeUpdate".equals(statementMethod) ? 0 : null);
            }
            return null;
        });

        CanonicalProjectionDAO.updateLocalAccountOrganizationAvailability(connection);

        assertTrue(sql[0].contains("canonical_org.status = 'ACTIVE'"));
        assertTrue(sql[0].contains("local_user.organization_id IS NULL"));
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
}
