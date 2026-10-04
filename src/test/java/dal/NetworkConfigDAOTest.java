package dal;

import java.lang.reflect.Proxy;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import config.NetworkConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NetworkConfigDAOTest {
    @Test
    void loadsAndValidatesTheSingletonNetworkConfiguration() throws Exception {
        Instant genesisTime = Instant.parse("2026-10-04T10:00:00.000Z");
        String publicKey = publicKey();
        Map<String, Object> values = Map.of(
                "network_id", "agritrace-test",
                "genesis_hash", "a".repeat(64),
                "initial_pow_difficulty", 2,
                "genesis_timestamp", Timestamp.from(genesisTime),
                "genesis_nonce", 19L,
                "genesis_admin_public_key", publicKey);

        NetworkConfiguration configuration = new NetworkConfigDAO(
                () -> connection(values, true)).loadRequired();

        assertEquals("agritrace-test", configuration.networkId());
        assertEquals(genesisTime, configuration.genesisTimestamp());
        assertEquals(19L, configuration.genesisNonce());
        assertEquals(publicKey, configuration.genesisAdminPublicKey());
    }

    @Test
    void failsExplicitlyWhenNetworkConfigurationIsMissing() {
        PersistenceException exception = assertThrows(
                PersistenceException.class,
                () -> new NetworkConfigDAO(() -> connection(Map.of(), false)).loadRequired());

        assertEquals("Network configuration row with id 1 is missing", exception.getMessage());
    }

    private Connection connection(Map<String, Object> values, boolean rowExists) {
        AtomicInteger cursor = new AtomicInteger();
        AtomicBoolean wasNull = new AtomicBoolean();
        ResultSet resultSet = proxy(ResultSet.class, (method, args) -> switch (method) {
            case "next" -> rowExists && cursor.getAndIncrement() == 0;
            case "getString" -> values.get(args[0]);
            case "getInt" -> values.get(args[0]);
            case "getLong" -> {
                Object value = values.get(args[0]);
                wasNull.set(value == null);
                yield value == null ? 0L : value;
            }
            case "getTimestamp" -> values.get(args[0]);
            case "wasNull" -> wasNull.get();
            default -> null;
        });
        PreparedStatement statement = proxy(PreparedStatement.class, (method, args) ->
                "executeQuery".equals(method) ? resultSet : null);
        return proxy(Connection.class, (method, args) ->
                "prepareStatement".equals(method) ? statement : null);
    }

    private String publicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return Base64.getEncoder().encodeToString(
                generator.generateKeyPair().getPublic().getEncoded());
    }

    private static <T> T proxy(Class<T> type, MethodHandler handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> handler.invoke(
                        method.getName(), args == null ? new Object[0] : args)));
    }

    @FunctionalInterface
    private interface MethodHandler {
        Object invoke(String method, Object[] args) throws Throwable;
    }
}
