package controller;

import dal.UserDAO;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Map;

final class FakeUserDataSource {
    private FakeUserDataSource() {
    }

    static UserDAO userDao(Map<String, UserDAO.UserCredential> users) {
        return new UserDAO(() -> proxy(Connection.class, (method, args) -> switch (method) {
            case "prepareStatement" -> statement(users);
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        }));
    }

    private static PreparedStatement statement(Map<String, UserDAO.UserCredential> users) {
        String[] username = {null};
        return proxy(PreparedStatement.class, (method, args) -> switch (method) {
            case "setString" -> {
                if ((Integer) args[0] == 1) {
                    username[0] = (String) args[1];
                }
                yield null;
            }
            case "executeQuery" -> resultSet(users.get(username[0]));
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static ResultSet resultSet(UserDAO.UserCredential user) {
        boolean[] consumed = {false};
        return proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> {
                if (user == null || consumed[0]) {
                    yield false;
                }
                consumed[0] = true;
                yield true;
            }
            case "getLong" -> user.userId();
            case "getString" -> switch ((String) args[0]) {
                case "username" -> user.username();
                case "password_hash" -> user.passwordHash();
                case "role" -> user.role();
                case "organization_id" -> user.organizationId();
                default -> throw new UnsupportedOperationException((String) args[0]);
            };
            case "getBoolean" -> switch ((String) args[0]) {
                case "is_active" -> user.active();
                case "organization_canonical" -> user.organizationCanonical();
                default -> throw new UnsupportedOperationException((String) args[0]);
            };
            case "close" -> null;
            default -> throw new UnsupportedOperationException(method);
        });
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
