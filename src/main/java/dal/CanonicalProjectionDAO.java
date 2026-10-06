package dal;

import blockchain.BlockValidationResult;
import blockchain.CanonicalJson;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import model.BatchEvent;
import model.BatchSnapshot;
import model.Block;
import model.EventType;
import model.GovernanceTransaction;
import model.GovernanceType;
import model.GovernedOrganization;
import model.LedgerTransaction;
import model.OrganizationKey;
import model.PeerRegistration;

final class CanonicalProjectionDAO {
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private CanonicalProjectionDAO() {
    }

    static void replace(
            Connection connection,
            BlockValidationResult state,
            List<String> transactionOrder
    ) throws SQLException {
        requireCompleteState(state, transactionOrder);
        Origins origins = origins(state, transactionOrder);

        clearProjections(connection);
        insertOrganizations(connection, state, origins);
        insertOrganizationKeys(connection, state, origins);
        insertPeers(connection, state, origins);
        updateLocalAccountOrganizationAvailability(connection);

        Map<String, BatchEvent> harvests = harvestEvents(state);
        insertBatches(connection, state, harvests);
        insertBatchEvents(connection, state, transactionOrder);
    }

    private static void requireCompleteState(
            BlockValidationResult state,
            List<String> transactionOrder
    ) {
        if (state.transactionsById().size() != state.transactionIds().size()
                || transactionOrder.size() != state.transactionIds().size()
                || !state.transactionIds().equals(state.transactionsById().keySet())
                || transactionOrder.stream().distinct().count() != transactionOrder.size()
                || !state.transactionIds().equals(java.util.Set.copyOf(transactionOrder))) {
            throw new IllegalArgumentException(
                    "Canonical projection rebuild requires every canonical transaction in block order");
        }
    }

    private static Origins origins(BlockValidationResult state, List<String> transactionOrder) {
        Origins origins = new Origins();
        for (String transactionId : transactionOrder) {
            LedgerTransaction ledgerTransaction = state.transactionsById().get(transactionId);
            if (!(ledgerTransaction instanceof GovernanceTransaction transaction)) {
                continue;
            }
            Map<String, String> data = transaction.data();
            switch (transaction.governanceType()) {
                case REGISTER_ORGANIZATION -> {
                    String organizationId = required(data, "organizationId");
                    origins.organizationRegisteredBy.putIfAbsent(organizationId, transactionId);
                    origins.organizationUpdatedBy.put(organizationId, transactionId);
                    String keyId = required(data, "keyId");
                    origins.keyRegisteredBy.putIfAbsent(keyId, transactionId);
                }
                case REGISTER_ORGANIZATION_KEY -> origins.keyRegisteredBy.putIfAbsent(
                        required(data, "keyId"), transactionId);
                case REVOKE_ORGANIZATION_KEY -> origins.keyRevokedBy.put(
                        required(data, "keyId"), transactionId);
                case SET_ORGANIZATION_STATUS -> origins.organizationUpdatedBy.put(
                        required(data, "organizationId"), transactionId);
                case REGISTER_PEER -> {
                    String peerId = required(data, "peerId");
                    origins.peerRegisteredBy.putIfAbsent(peerId, transactionId);
                    origins.peerUpdatedBy.put(peerId, transactionId);
                }
                case REVOKE_PEER -> origins.peerUpdatedBy.put(required(data, "peerId"), transactionId);
            }
        }
        return origins;
    }

    private static void clearProjections(Connection connection) throws SQLException {
        execute(connection, "DELETE FROM batch_events");
        execute(connection, "DELETE FROM batches");
        execute(connection, "DELETE FROM authorized_peers");
        execute(connection, "DELETE FROM organization_keys");
        execute(connection, "DELETE FROM organizations");
    }

    private static void insertOrganizations(
            Connection connection,
            BlockValidationResult state,
            Origins origins
    ) throws SQLException {
        String sql = """
                INSERT INTO organizations
                    (organization_id, organization_type, name, province, status,
                     registered_by_tx_id, updated_by_tx_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (GovernedOrganization organization : state.governanceRegistry().organizations().values()) {
                statement.setString(1, organization.organizationId());
                statement.setString(2, organization.type().name());
                statement.setString(3, organization.name());
                statement.setString(4, organization.province());
                statement.setString(5, organization.status().name());
                setNullableString(statement, 6,
                        origins.organizationRegisteredBy.get(organization.organizationId()));
                setNullableString(statement, 7,
                        origins.organizationUpdatedBy.get(organization.organizationId()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertOrganizationKeys(
            Connection connection,
            BlockValidationResult state,
            Origins origins
    ) throws SQLException {
        String sql = """
                INSERT INTO organization_keys
                    (key_id, organization_id, algorithm, public_key, valid_from_height,
                     revoked_at_height, registered_by_tx_id, revoked_by_tx_id)
                VALUES (?, ?, 'ECDSA_P256_SHA256', ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (OrganizationKey key : state.governanceRegistry().organizationKeys().values()) {
                statement.setString(1, key.keyId());
                statement.setString(2, key.organizationId());
                statement.setString(3, key.publicKey());
                statement.setLong(4, key.validFromHeight());
                if (key.revokedAtHeight() == null) {
                    statement.setNull(5, Types.BIGINT);
                } else {
                    statement.setLong(5, key.revokedAtHeight());
                }
                setNullableString(statement, 6, origins.keyRegisteredBy.get(key.keyId()));
                setNullableString(statement, 7, origins.keyRevokedBy.get(key.keyId()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void insertPeers(
            Connection connection,
            BlockValidationResult state,
            Origins origins
    ) throws SQLException {
        String sql = """
                INSERT INTO authorized_peers
                    (peer_id, organization_id, endpoint, tls_certificate_fingerprint, status,
                     registered_by_tx_id, updated_by_tx_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (PeerRegistration peer : state.governanceRegistry().peers().values()) {
                statement.setString(1, peer.peerId());
                statement.setString(2, peer.organizationId());
                statement.setString(3, peer.endpoint());
                statement.setString(4, peer.tlsCertificateFingerprint());
                statement.setString(5, peer.active() ? "ACTIVE" : "REVOKED");
                setNullableString(statement, 6, origins.peerRegisteredBy.get(peer.peerId()));
                setNullableString(statement, 7, origins.peerUpdatedBy.get(peer.peerId()));
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    static void updateLocalAccountOrganizationAvailability(Connection connection) throws SQLException {
        execute(connection, """
                UPDATE users AS local_user
                SET organization_canonical = (
                    local_user.organization_id IS NULL
                    OR EXISTS (
                        SELECT 1
                        FROM organizations AS canonical_org
                        WHERE canonical_org.organization_id = local_user.organization_id
                          AND canonical_org.status = 'ACTIVE'
                    )
                )
                """);
    }

    private static Map<String, BatchEvent> harvestEvents(BlockValidationResult state) {
        Map<String, BatchEvent> harvests = new HashMap<>();
        for (BatchEvent event : state.eventsByTransaction().values()) {
            if (event.eventType() == EventType.HARVESTED
                    && harvests.putIfAbsent(event.batchCode(), event) != null) {
                throw new IllegalArgumentException("Canonical history contains multiple harvests for a batch");
            }
        }
        if (!harvests.keySet().equals(state.batches().keySet())) {
            throw new IllegalArgumentException("Every canonical batch projection must have one harvest event");
        }
        return harvests;
    }

    private static void insertBatches(
            Connection connection,
            BlockValidationResult state,
            Map<String, BatchEvent> harvests
    ) throws SQLException {
        String sql = """
                INSERT INTO batches
                    (batch_code, product_type, variety, harvest_date, quantity, quantity_unit,
                     farm_name, province, farmer_organization_id, current_holder_organization_id,
                     current_status, harvest_tx_id, last_event_tx_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (Map.Entry<String, BatchSnapshot> entry : state.batches().entrySet()) {
                BatchSnapshot snapshot = entry.getValue();
                BatchEvent harvest = harvests.get(entry.getKey());
                Map<String, Object> data = harvest.data();
                statement.setString(1, snapshot.batchCode());
                statement.setString(2, requiredString(data, "productType"));
                statement.setString(3, requiredString(data, "variety"));
                statement.setDate(4, java.sql.Date.valueOf(
                        java.time.LocalDate.parse(requiredString(data, "harvestDate"))));
                statement.setBigDecimal(5, new BigDecimal(requiredString(data, "quantity")));
                statement.setString(6, requiredString(data, "quantityUnit"));
                statement.setString(7, requiredString(data, "farmName"));
                statement.setString(8, requiredString(data, "province"));
                statement.setString(9, snapshot.farmerOrganizationId());
                statement.setString(10, snapshot.currentHolderOrganizationId());
                statement.setString(11, snapshot.state().name());
                statement.setString(12, harvest.transactionId());
                statement.setString(13, snapshot.lastEventTransactionId());
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (IllegalArgumentException exception) {
            throw new PersistenceException("Canonical batch state cannot be projected", exception);
        }
    }

    private static void insertBatchEvents(
            Connection connection,
            BlockValidationResult state,
            List<String> transactionOrder
    ) throws SQLException {
        String sql = """
                INSERT INTO batch_events
                    (tx_id, batch_code, event_type, actor_organization_id, event_time,
                     correction_of_tx_id, public_payload)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (String transactionId : transactionOrder) {
                LedgerTransaction ledgerTransaction = state.transactionsById().get(transactionId);
                if (!(ledgerTransaction instanceof BatchEvent event)) {
                    continue;
                }
                statement.setString(1, event.transactionId());
                statement.setString(2, event.batchCode());
                statement.setString(3, event.eventType().name());
                statement.setString(4, actorOrganizationId(event));
                statement.setTimestamp(
                        5,
                        Timestamp.from(event.eventTime()),
                        java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC")));
                setNullableString(statement, 6, nullableStringData(event, "correctionOfTxId"));
                statement.setString(7, CanonicalJson.canonicalize(GSON.toJson(event.data())));
                statement.addBatch();
            }
            statement.executeBatch();
        } catch (IllegalArgumentException exception) {
            throw new PersistenceException("Canonical batch events cannot be projected", exception);
        }
    }

    private static String actorOrganizationId(BatchEvent event) {
        if (event.eventType() == EventType.SHIPPED) {
            return requiredString(event.data(), "senderOrganizationId");
        }
        if (event.signatures().isEmpty()) {
            throw new IllegalArgumentException("A canonical batch event must have a signer");
        }
        return event.signatures().get(0).organizationId();
    }

    private static String nullableStringData(BatchEvent event, String key) {
        Object value = event.data().get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string when present");
        }
        return text;
    }

    private static String requiredString(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Canonical batch event is missing " + key);
        }
        return text;
    }

    private static String required(Map<String, String> data, String key) {
        String value = data.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Canonical governance transaction is missing " + key);
        }
        return value;
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.CHAR);
        } else {
            statement.setString(index, value);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    private static final class Origins {
        private final Map<String, String> organizationRegisteredBy = new HashMap<>();
        private final Map<String, String> organizationUpdatedBy = new HashMap<>();
        private final Map<String, String> keyRegisteredBy = new HashMap<>();
        private final Map<String, String> keyRevokedBy = new HashMap<>();
        private final Map<String, String> peerRegisteredBy = new HashMap<>();
        private final Map<String, String> peerUpdatedBy = new HashMap<>();
    }
}
