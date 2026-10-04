package dal;

import blockchain.CanonicalJson;
import blockchain.TransactionCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TimeZone;
import model.BatchEvent;
import model.EventType;
import model.ShipmentProposal;
import model.SignatureEnvelope;
import service.ShipmentProposalRepository;
import util.DBConnection;

public final class ShipmentProposalDAO implements ShipmentProposalRepository {
    private static final String INSERT_PROPOSAL = """
            INSERT INTO shipment_proposals
                (proposal_id, event_id, batch_code, sender_organization_id,
                 carrier_organization_id, payload, payload_hash, status, expires_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'AWAITING_CARRIER', ?)
            """;
    private static final String INSERT_SIGNATURE = """
            INSERT INTO shipment_proposal_signatures
                (proposal_id, signer_organization_id, signer_role, key_id, signature, signed_at)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_PROPOSAL = """
            SELECT proposal_id, event_id, batch_code, payload, status, expires_at, submitted_tx_id
            FROM shipment_proposals
            WHERE proposal_id = ?
            """;
    private static final String FIND_INBOX = """
            SELECT proposal_id
            FROM shipment_proposals
            WHERE carrier_organization_id = ?
              AND (status = 'READY' OR (status = 'AWAITING_CARRIER' AND expires_at > ?))
            ORDER BY created_at, proposal_id
            """;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private final String networkId;
    private final ConnectionProvider connectionProvider;
    private final Clock clock;

    public ShipmentProposalDAO(String networkId) {
        this(networkId, DBConnection::getConnection, Clock.systemUTC());
    }

    public ShipmentProposalDAO(
            String networkId,
            ConnectionProvider connectionProvider,
            Clock clock
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        this.networkId = networkId;
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public void insert(ShipmentProposal proposal) {
        requireNewProposal(proposal);
        Instant now = clock.instant();
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement statement = connection.prepareStatement(INSERT_PROPOSAL)) {
                    statement.setString(1, proposal.proposalId());
                    statement.setString(2, proposal.event().eventId());
                    statement.setString(3, proposal.event().batchCode());
                    statement.setString(4, dataString(proposal.event(), "senderOrganizationId"));
                    statement.setString(5, dataString(proposal.event(), "carrierOrganizationId"));
                    statement.setString(6, proposalPayload(proposal));
                    statement.setString(7, TransactionCodec.payloadHash(networkId, proposal.event()));
                    statement.setTimestamp(8, Timestamp.from(proposal.expiresAt()), utcCalendar());
                    statement.executeUpdate();
                }
                insertSignature(connection, proposal.proposalId(),
                        proposal.event().signatures().get(0), "SENDER", now);
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            if (exception.getSQLState() != null && exception.getSQLState().startsWith("23")) {
                throw new DuplicateTransactionException(
                        "DUPLICATE_SHIPMENT_PROPOSAL",
                        "Proposal ID or event ID is already in use",
                        exception);
            }
            throw new PersistenceException("Could not save shipment proposal", exception);
        }
    }

    @Override
    public Optional<ShipmentProposal> find(String proposalId) {
        if (proposalId == null || proposalId.isBlank()) {
            throw new IllegalArgumentException("proposalId must not be blank");
        }
        try (Connection connection = connectionProvider.getConnection()) {
            return load(connection, proposalId);
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load shipment proposal", exception);
        }
    }

    @Override
    public List<ShipmentProposal> findUnexpiredForCarrier(String carrierOrganizationId) {
        if (carrierOrganizationId == null || carrierOrganizationId.isBlank()) {
            throw new IllegalArgumentException("carrierOrganizationId must not be blank");
        }
        List<String> proposalIds = new ArrayList<>();
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement(FIND_INBOX)) {
            statement.setString(1, carrierOrganizationId);
            statement.setTimestamp(2, Timestamp.from(clock.instant()), utcCalendar());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    proposalIds.add(rows.getString("proposal_id"));
                }
            }
            List<ShipmentProposal> proposals = new ArrayList<>(proposalIds.size());
            for (String proposalId : proposalIds) {
                load(connection, proposalId).ifPresent(proposals::add);
            }
            return List.copyOf(proposals);
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load shipment inbox", exception);
        }
    }

    @Override
    public ShipmentProposal endorse(String proposalId, SignatureEnvelope carrierSignature) {
        if (proposalId == null || proposalId.isBlank() || carrierSignature == null) {
            throw new IllegalArgumentException("proposalId and carrier signature are required");
        }
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                Optional<ShipmentProposal> current = loadForUpdate(connection, proposalId);
                ShipmentProposal proposal = current.orElseThrow(
                        () -> new IllegalArgumentException("shipment proposal was not found"));
                if (proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER) {
                    boolean sameEndorsement = proposal.event().signatures().contains(carrierSignature);
                    if ((proposal.status() == ShipmentProposal.Status.READY
                            || proposal.status() == ShipmentProposal.Status.SUBMITTED)
                            && sameEndorsement) {
                        connection.commit();
                        return proposal;
                    }
                    throw new IllegalStateException("shipment proposal is no longer awaiting endorsement");
                }
                insertSignature(connection, proposalId, carrierSignature, "CARRIER", clock.instant());
                try (PreparedStatement statement = connection.prepareStatement("""
                        UPDATE shipment_proposals
                        SET status = 'READY'
                        WHERE proposal_id = ? AND status = 'AWAITING_CARRIER'
                        """)) {
                    statement.setString(1, proposalId);
                    if (statement.executeUpdate() != 1) {
                        throw new IllegalStateException("shipment proposal status changed concurrently");
                    }
                }
                connection.commit();
                List<SignatureEnvelope> signatures = new ArrayList<>(proposal.event().signatures());
                signatures.add(carrierSignature);
                BatchEvent event = new BatchEvent(
                        "pending", proposal.event().eventId(), proposal.event().batchCode(),
                        EventType.SHIPPED, proposal.event().eventTime(), proposal.event().data(), signatures);
                return new ShipmentProposal(
                        proposal.proposalId(), event, proposal.expiresAt(),
                        ShipmentProposal.Status.READY, null);
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                throw exception;
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not endorse shipment proposal", exception);
        }
    }

    @Override
    public void markSubmitted(String proposalId, String transactionId) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE shipment_proposals
                     SET status = 'SUBMITTED', submitted_tx_id = ?
                     WHERE proposal_id = ? AND status IN ('READY', 'SUBMITTED')
                     """)) {
            statement.setString(1, transactionId);
            statement.setString(2, proposalId);
            if (statement.executeUpdate() != 1) {
                throw new PersistenceException("Shipment proposal could not be marked submitted");
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not mark shipment proposal submitted", exception);
        }
    }

    @Override
    public void markExpired(String proposalId) {
        try (Connection connection = connectionProvider.getConnection();
             PreparedStatement statement = connection.prepareStatement("""
                     UPDATE shipment_proposals
                     SET status = 'EXPIRED'
                     WHERE proposal_id = ? AND status = 'AWAITING_CARRIER' AND expires_at <= ?
                     """)) {
            statement.setString(1, proposalId);
            statement.setTimestamp(2, Timestamp.from(clock.instant()), utcCalendar());
            statement.executeUpdate();
        } catch (SQLException exception) {
            throw new PersistenceException("Could not mark shipment proposal expired", exception);
        }
    }

    private Optional<ShipmentProposal> loadForUpdate(Connection connection, String proposalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                FIND_PROPOSAL + " FOR UPDATE")) {
            statement.setString(1, proposalId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(decode(connection, row)) : Optional.empty();
            }
        }
    }

    private Optional<ShipmentProposal> load(Connection connection, String proposalId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_PROPOSAL)) {
            statement.setString(1, proposalId);
            try (ResultSet row = statement.executeQuery()) {
                return row.next() ? Optional.of(decode(connection, row)) : Optional.empty();
            }
        }
    }

    private ShipmentProposal decode(Connection connection, ResultSet row) throws SQLException {
        String proposalId = row.getString("proposal_id");
        JsonObject payload = JsonParser.parseString(row.getString("payload")).getAsJsonObject();
        String eventId = payload.get("eventId").getAsString();
        String batchCode = payload.get("batchCode").getAsString();
        Instant eventTime = Instant.parse(payload.get("eventTime").getAsString());
        Instant expiresAt = row.getTimestamp("expires_at", utcCalendar()).toInstant();
        Map<String, Object> data = objectMap(payload.getAsJsonObject("data"));
        List<SignatureEnvelope> signatures = loadSignatures(connection, proposalId);
        String statusValue = row.getString("status");
        ShipmentProposal.Status status = ShipmentProposal.Status.valueOf(statusValue);
        String submittedTransactionId = row.getString("submitted_tx_id");
        BatchEvent event = new BatchEvent(
                submittedTransactionId == null ? "pending" : submittedTransactionId,
                eventId,
                batchCode,
                EventType.SHIPPED,
                eventTime,
                data,
                signatures);
        return new ShipmentProposal(
                proposalId, event, expiresAt, status, submittedTransactionId);
    }

    private List<SignatureEnvelope> loadSignatures(Connection connection, String proposalId)
            throws SQLException {
        List<SignatureEnvelope> signatures = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT signer_organization_id, key_id, signer_role, signature
                FROM shipment_proposal_signatures
                WHERE proposal_id = ?
                ORDER BY CASE signer_role WHEN 'SENDER' THEN 0 ELSE 1 END
                """)) {
            statement.setString(1, proposalId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String role = rows.getString("signer_role");
                    signatures.add(new SignatureEnvelope(
                            rows.getString("signer_organization_id"),
                            rows.getString("key_id"),
                            "SENDER".equals(role) ? "SHIPMENT_SENDER" : "SHIPMENT_CARRIER",
                            rows.getString("signature")));
                }
            }
        }
        return List.copyOf(signatures);
    }

    private void insertSignature(
            Connection connection,
            String proposalId,
            SignatureEnvelope signature,
            String role,
            Instant signedAt
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_SIGNATURE)) {
            statement.setString(1, proposalId);
            statement.setString(2, signature.organizationId());
            statement.setString(3, role);
            statement.setString(4, signature.keyId());
            statement.setString(5, signature.signature());
            statement.setTimestamp(6, Timestamp.from(signedAt), utcCalendar());
            statement.executeUpdate();
        }
    }

    private void requireNewProposal(ShipmentProposal proposal) {
        if (proposal == null || proposal.status() != ShipmentProposal.Status.AWAITING_CARRIER
                || proposal.event().signatures().size() != 1) {
            throw new IllegalArgumentException("only a new sender-signed proposal can be inserted");
        }
    }

    private String proposalPayload(ShipmentProposal proposal) {
        JsonObject payload = new JsonObject();
        payload.addProperty("eventId", proposal.event().eventId());
        payload.addProperty("batchCode", proposal.event().batchCode());
        payload.addProperty("eventTime", proposal.event().eventTime().toString());
        payload.add("data", GSON.toJsonTree(proposal.event().data()));
        payload.addProperty("expiresAt", proposal.expiresAt().toString());
        return CanonicalJson.canonicalize(GSON.toJson(payload));
    }

    private Map<String, Object> objectMap(JsonObject object) {
        return GSON.fromJson(object, new com.google.gson.reflect.TypeToken<Map<String, Object>>() { }.getType());
    }

    private String dataString(BatchEvent event, String key) {
        Object value = event.data().get(key);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(key + " must be a string");
        }
        return text;
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private void rollback(Connection connection, Exception original) {
        try {
            connection.rollback();
        } catch (SQLException rollbackFailure) {
            original.addSuppressed(rollbackFailure);
        }
    }
}
