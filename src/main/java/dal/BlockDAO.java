package dal;

import blockchain.BlockCodec;
import blockchain.BlockRepository;
import blockchain.BlockValidationResult;
import blockchain.ChainForkChoice;
import blockchain.GovernanceCodec;
import blockchain.LedgerTransactionCodec;
import blockchain.ProofOfWork;
import blockchain.TransactionCodec;
import com.google.gson.JsonParseException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Clock;
import java.time.DateTimeException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TimeZone;
import model.BatchEvent;
import model.Block;
import model.BlockHeader;
import model.GovernanceTransaction;
import model.LedgerTransaction;
import util.DBConnection;

public final class BlockDAO implements BlockRepository {
    private static final String FIND_BLOCK = """
            SELECT block_hash, height, previous_hash, block_timestamp, nonce, difficulty,
                   transactions_hash, cumulative_work, is_canonical
            FROM blockchain_blocks
            WHERE block_hash = ?
            FOR UPDATE
            """;
    private static final String FIND_PARENT = """
            SELECT block_hash, height, difficulty, cumulative_work
            FROM blockchain_blocks
            WHERE block_hash = ?
            FOR UPDATE
            """;
    private static final String FIND_CANONICAL_TIP = """
            SELECT block_hash, height, cumulative_work
            FROM blockchain_blocks
            WHERE is_canonical = TRUE
            ORDER BY height DESC
            LIMIT 1
            FOR UPDATE
            """;
    private static final String FIND_CANONICAL_TIP_READ = """
            SELECT block_hash
            FROM blockchain_blocks
            WHERE is_canonical = TRUE
            ORDER BY height DESC
            LIMIT 1
            """;
    private static final String INSERT_BLOCK = """
            INSERT INTO blockchain_blocks
                (block_hash, height, previous_hash, block_timestamp, nonce, difficulty,
                 transactions_hash, cumulative_work, is_canonical)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, FALSE)
            """;
    private static final String INSERT_BLOCK_TRANSACTION = """
            INSERT INTO block_transactions (block_hash, tx_id, transaction_index)
            VALUES (?, ?, ?)
            """;
    private static final String FIND_BLOCK_TRANSACTIONS = """
            SELECT tx_id
            FROM block_transactions
            WHERE block_hash = ?
            ORDER BY transaction_index
            """;
    private static final String FIND_CANONICAL_TRANSACTION_IDS = """
            SELECT bt.tx_id
            FROM blockchain_blocks b
            JOIN block_transactions bt ON bt.block_hash = b.block_hash
            WHERE b.is_canonical = TRUE
            ORDER BY b.height, bt.transaction_index
            """;
    private static final String FIND_PATH_BLOCK = """
            SELECT block_hash, previous_hash, height
            FROM blockchain_blocks
            WHERE block_hash = ?
            FOR UPDATE
            """;
    private static final String FIND_STORED_PATH_BLOCK = """
            SELECT block_hash, previous_hash, height
            FROM blockchain_blocks
            WHERE block_hash = ?
            """;
    private static final String FIND_BLOCK_TRANSACTIONS_FOR_PATH = """
            SELECT tx_id
            FROM block_transactions
            WHERE block_hash = ?
            ORDER BY transaction_index
            """;
    private static final String FIND_BLOCK_AND_TRANSACTIONS = """
            SELECT b.block_hash, b.height, b.previous_hash, b.block_timestamp, b.nonce,
                   b.difficulty, b.transactions_hash, b.cumulative_work,
                   t.tx_id, t.event_id, t.tx_type, t.payload, t.payload_hash, t.signatures
            FROM blockchain_blocks b
            LEFT JOIN block_transactions bt ON bt.block_hash = b.block_hash
            LEFT JOIN blockchain_transactions t ON t.tx_id = bt.tx_id
            WHERE b.block_hash = ?
            ORDER BY bt.transaction_index
            """;
    private static final String INSERT_TRANSACTION = """
            INSERT INTO blockchain_transactions
                (tx_id, event_id, tx_type, payload, payload_hash, signatures, submitted_at)
            VALUES (?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FIND_TRANSACTION = """
            SELECT event_id, tx_type, payload_hash
            FROM blockchain_transactions
            WHERE tx_id = ?
            FOR UPDATE
            """;
    private static final String ENSURE_PENDING_STATUS = """
            INSERT INTO node_transaction_status (tx_id, status)
            VALUES (?, 'PENDING')
            ON DUPLICATE KEY UPDATE tx_id = tx_id
            """;
    private static final String RESET_CANONICAL =
            "UPDATE blockchain_blocks SET is_canonical = FALSE WHERE is_canonical = TRUE";
    private static final String MARK_CANONICAL =
            "UPDATE blockchain_blocks SET is_canonical = TRUE WHERE block_hash = ?";
    private static final String MARK_PENDING = """
            UPDATE node_transaction_status
            SET status = 'PENDING', rejection_code = NULL
            WHERE tx_id = ? AND status = 'CONFIRMED'
            """;
    private static final String MARK_CONFIRMED = """
            UPDATE node_transaction_status
            SET status = 'CONFIRMED', rejection_code = NULL
            WHERE tx_id = ?
            """;
    private static final String ENSURE_POOL_ENTRY = """
            INSERT INTO transaction_pool (tx_id, source_node_id)
            VALUES (?, NULL)
            ON DUPLICATE KEY UPDATE tx_id = tx_id
            """;
    private static final String REMOVE_POOL_ENTRY =
            "DELETE FROM transaction_pool WHERE tx_id = ?";

    private final ConnectionProvider connectionProvider;
    private final Clock clock;
    private final String networkId;
    private final String expectedGenesisHash;

    public BlockDAO(String networkId, String expectedGenesisHash) {
        this(DBConnection::getConnection, Clock.systemUTC(), networkId, expectedGenesisHash);
    }

    public BlockDAO(
            ConnectionProvider connectionProvider,
            Clock clock,
            String networkId,
            String expectedGenesisHash
    ) {
        this.connectionProvider = Objects.requireNonNull(connectionProvider, "connectionProvider");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        if (expectedGenesisHash == null || !expectedGenesisHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("expectedGenesisHash must be a lowercase SHA-256 hash");
        }
        this.networkId = networkId;
        this.expectedGenesisHash = expectedGenesisHash;
    }

    @Override
    public BlockRepository.StoreResult storeValidatedBlock(BlockValidationResult validation) {
        validateResult(validation);
        Block block = validation.block();
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            try {
                verifyParent(connection, block);
                boolean inserted = insertBlockAndTransactions(connection, validation);
                List<String> branch = canonicalBranch(connection, block);
                CanonicalTip currentTip = findCanonicalTip(connection);
                boolean becomesCanonical = currentTip == null
                        || ChainForkChoice.isPreferred(
                                block.cumulativeWork(),
                                block.hash(),
                                currentTip.cumulativeWork(),
                                currentTip.hash());
                if (!becomesCanonical) {
                    connection.commit();
                    return inserted
                            ? BlockRepository.StoreResult.FORK_STORED
                            : BlockRepository.StoreResult.ALREADY_PRESENT;
                }

                List<String> orderedTransactions = transactionOrder(connection, branch);
                List<String> previousCanonicalTransactions = canonicalTransactionIds(connection);
                replaceCanonicalFlags(connection, branch);
                updateTransactionStatuses(connection, orderedTransactions, previousCanonicalTransactions);
                CanonicalProjectionDAO.replace(connection, validation, orderedTransactions);
                connection.commit();
                return BlockRepository.StoreResult.CANONICAL_TIP_UPDATED;
            } catch (SQLException | RuntimeException exception) {
                rollback(connection, exception);
                if (exception instanceof PersistenceException persistenceException) {
                    throw persistenceException;
                }
                if (exception instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw new PersistenceException("Could not persist validated block", exception);
            }
        } catch (SQLException exception) {
            throw new PersistenceException("Could not access the block database", exception);
        }
    }

    @Override
    public List<BlockRepository.StoredBlock> loadCanonicalChain() {
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            String tipHash;
            try (PreparedStatement statement = connection.prepareStatement(FIND_CANONICAL_TIP_READ);
                 ResultSet result = statement.executeQuery()) {
                tipHash = result.next() ? result.getString("block_hash") : null;
            }
            List<BlockRepository.StoredBlock> chain =
                    tipHash == null ? List.of() : loadBranch(connection, tipHash);
            connection.commit();
            return chain;
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load the canonical blockchain", exception);
        }
    }

    @Override
    public List<BlockRepository.StoredBlock> loadBranch(String tipHash) {
        if (tipHash == null || !tipHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("tipHash must be a lowercase SHA-256 digest");
        }
        try (Connection connection = connectionProvider.getConnection()) {
            connection.setAutoCommit(false);
            List<BlockRepository.StoredBlock> chain = loadBranch(connection, tipHash);
            connection.commit();
            return chain;
        } catch (SQLException exception) {
            throw new PersistenceException("Could not load the requested block branch", exception);
        }
    }

    private List<BlockRepository.StoredBlock> loadBranch(Connection connection, String tipHash)
            throws SQLException {
        List<String> descendingBranch = new ArrayList<>();
        String currentHash = tipHash;
        long expectedHeight = -1;
        while (currentHash != null) {
            try (PreparedStatement statement = connection.prepareStatement(FIND_STORED_PATH_BLOCK)) {
                statement.setString(1, currentHash);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new PersistenceException("Stored branch has a missing ancestor");
                    }
                    long height = result.getLong("height");
                    if (expectedHeight >= 0 && height != expectedHeight) {
                        throw new PersistenceException("Stored branch has inconsistent block heights");
                    }
                    descendingBranch.add(currentHash);
                    currentHash = result.getString("previous_hash");
                    if (height == 0) {
                        if (currentHash != null || !expectedGenesisHash.equals(descendingBranch.get(
                                descendingBranch.size() - 1))) {
                            throw new PersistenceException("Stored branch does not terminate at network genesis");
                        }
                    } else {
                        if (currentHash == null) {
                            throw new PersistenceException("Stored branch terminates before network genesis");
                        }
                        expectedHeight = height - 1;
                    }
                }
            }
        }
        Collections.reverse(descendingBranch);

        List<BlockRepository.StoredBlock> chain = new ArrayList<>(descendingBranch.size());
        BigInteger parentWork = BigInteger.ZERO;
        String parentHash = null;
        int networkDifficulty = -1;
        Set<String> transactionIds = new HashSet<>();
        Set<String> eventIds = new HashSet<>();
        java.time.Instant previousTimestamp = null;
        for (int index = 0; index < descendingBranch.size(); index++) {
            BlockRepository.StoredBlock persistedBlock = loadBlock(connection, descendingBranch.get(index));
            Block block = persistedBlock.block();
            if (index == 0) {
                networkDifficulty = block.header().difficulty();
            }
            if (block.header().height() != index
                    || !Objects.equals(block.header().previousHash(), parentHash)
                    || !block.cumulativeWork().equals(parentWork.add(
                            ProofOfWork.workForDifficulty(block.header().difficulty())))
                    || block.header().difficulty() < 1
                    || block.header().difficulty() > BlockHeader.MAX_DIFFICULTY
                    || block.header().difficulty() != networkDifficulty
                    || !block.hash().equals(BlockCodec.hashHeader(block.header()))
                    || !block.header().transactionsHash().equals(
                            BlockCodec.transactionsHash(block.transactionIds()))
                    || !ProofOfWork.hasValidProof(block)
                    || (previousTimestamp != null
                            && !block.header().timestamp().isAfter(previousTimestamp))) {
                throw new PersistenceException("Stored block branch failed structural integrity checks");
            }
            if (!networkId.equals(block.header().networkId())) {
                throw new PersistenceException("Stored block branch contains a different network ID");
            }
            for (LedgerTransaction transaction : persistedBlock.transactions()) {
                if (!transactionIds.add(transaction.transactionId()) || !eventIds.add(transaction.eventId())) {
                    throw new PersistenceException("Stored block branch contains duplicate transaction or event IDs");
                }
            }
            parentHash = block.hash();
            parentWork = block.cumulativeWork();
            previousTimestamp = block.header().timestamp();
            chain.add(persistedBlock);
        }
        return List.copyOf(chain);
    }

    private BlockRepository.StoredBlock loadBlock(Connection connection, String blockHash)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BLOCK_AND_TRANSACTIONS)) {
            statement.setString(1, blockHash);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Stored block disappeared while loading its branch");
                }
                long height = result.getLong("height");
                Timestamp timestamp = result.getTimestamp("block_timestamp", utcCalendar());
                if (timestamp == null) {
                    throw new PersistenceException("Stored block has no timestamp");
                }
                BlockHeader header = new BlockHeader(
                        networkId,
                        height,
                        result.getString("previous_hash"),
                        timestamp.toInstant(),
                        result.getLong("nonce"),
                        result.getInt("difficulty"),
                        result.getString("transactions_hash"));
                BigInteger cumulativeWork = new BigInteger(result.getString("cumulative_work"));
                List<LedgerTransaction> transactions = new ArrayList<>();
                List<String> transactionIds = new ArrayList<>();
                do {
                    String transactionId = result.getString("tx_id");
                    if (transactionId != null) {
                        LedgerTransaction transaction = LedgerTransactionCodec.decode(
                                networkId,
                                transactionId,
                                result.getString("event_id"),
                                result.getString("tx_type"),
                                result.getString("payload"),
                                result.getString("payload_hash"),
                                result.getString("signatures"));
                        transactionIds.add(transactionId);
                        transactions.add(transaction);
                    }
                } while (result.next());
                Block block = new Block(
                        header,
                        transactionIds,
                        cumulativeWork,
                        blockHash);
                return new BlockRepository.StoredBlock(block, transactions);
            } catch (IllegalArgumentException | IllegalStateException
                    | DateTimeException | JsonParseException exception) {
                throw new PersistenceException("Stored block or transaction data is malformed", exception);
            }
        }
    }

    private void validateResult(BlockValidationResult validation) {
        if (validation == null) {
            throw new IllegalArgumentException("validation must not be null");
        }
        Block block = validation.block();
        BlockHeader header = block.header();
        if (!networkId.equals(header.networkId())) {
            throw new IllegalArgumentException("Validated block belongs to a different network");
        }
        if (!block.hash().equals(BlockCodec.hashHeader(header))) {
            throw new IllegalArgumentException("Validated block header hash is inconsistent");
        }
        if (!ProofOfWork.hasValidProof(block)) {
            throw new IllegalArgumentException("Validated block does not meet its proof-of-work target");
        }
        if (validation.transactionsById().size() != validation.transactionIds().size()
                || !validation.transactionsById().keySet().equals(validation.transactionIds())) {
            throw new IllegalArgumentException("Validated block state must include the full transaction history");
        }
        Set<String> observedEventIds = new HashSet<>();
        for (Map.Entry<String, LedgerTransaction> entry : validation.transactionsById().entrySet()) {
            if (!entry.getKey().equals(entry.getValue().transactionId())
                    || !observedEventIds.add(entry.getValue().eventId())) {
                throw new IllegalArgumentException("Validated transaction history has inconsistent or duplicate IDs");
            }
        }
        if (!observedEventIds.equals(validation.eventIds())) {
            throw new IllegalArgumentException("Validated event history does not match its transaction bodies");
        }
        for (Map.Entry<String, BatchEvent> entry : validation.eventsByTransaction().entrySet()) {
            if (!(validation.transactionsById().get(entry.getKey()) instanceof BatchEvent event)
                    || !event.equals(entry.getValue())) {
                throw new IllegalArgumentException("Validated batch event history is inconsistent");
            }
        }
        if (!header.transactionsHash().equals(BlockCodec.transactionsHash(block.transactionIds()))) {
            throw new IllegalArgumentException("Validated block transaction commitment is inconsistent");
        }
        for (String transactionId : block.transactionIds()) {
            LedgerTransaction transaction = validation.transactionsById().get(transactionId);
            if (transaction == null || !transactionId.equals(transaction.transactionId())) {
                throw new IllegalArgumentException("Validated block is missing a committed transaction body");
            }
            encodeTransaction(transaction);
        }
        if (header.height() == 0 && (!expectedGenesisHash.equals(block.hash())
                || header.previousHash() != null)) {
            throw new IllegalArgumentException("Block does not match the configured network genesis");
        }
        if (header.height() > 0 && header.previousHash() == null) {
            throw new IllegalArgumentException("Non-genesis block must identify its parent");
        }
    }

    private void verifyParent(Connection connection, Block block) throws SQLException {
        BlockHeader header = block.header();
        if (header.height() == 0) {
            if (header.previousHash() != null
                    || !block.cumulativeWork().equals(ProofOfWork.workForDifficulty(header.difficulty()))) {
                throw new IllegalArgumentException("Genesis block linkage or cumulative work is invalid");
            }
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement(FIND_PARENT)) {
            statement.setString(1, header.previousHash());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new PersistenceException("Validated block parent has not been persisted");
                }
                long parentHeight = result.getLong("height");
                BigInteger parentWork = new BigInteger(result.getString("cumulative_work"));
                BigInteger expectedWork = parentWork.add(
                        ProofOfWork.workForDifficulty(header.difficulty()));
                if (parentHeight == Long.MAX_VALUE || header.height() != parentHeight + 1
                        || header.difficulty() != result.getInt("difficulty")
                        || !block.cumulativeWork().equals(expectedWork)) {
                    throw new IllegalArgumentException("Validated block height or cumulative work is inconsistent");
                }
            }
        }
    }

    private boolean insertBlockAndTransactions(Connection connection, BlockValidationResult validation)
            throws SQLException {
        Block block = validation.block();
        boolean inserted = !blockExists(connection, block);
        for (String transactionId : block.transactionIds()) {
            insertOrVerifyTransaction(connection, validation.transactionsById().get(transactionId));
        }
        if (inserted) {
            insertBlock(connection, block);
            insertBlockTransactions(connection, block);
        } else if (!block.transactionIds().equals(findBlockTransactions(connection, block.hash()))) {
            throw new PersistenceException("Stored block transaction links do not match the validated block");
        }
        for (String transactionId : block.transactionIds()) {
            ensurePendingStatus(connection, transactionId);
        }
        return inserted;
    }

    private boolean blockExists(Connection connection, Block block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BLOCK)) {
            statement.setString(1, block.hash());
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return false;
                }
                Timestamp timestamp = result.getTimestamp("block_timestamp", utcCalendar());
                boolean same = result.getLong("height") == block.header().height()
                        && Objects.equals(result.getString("previous_hash"), block.header().previousHash())
                        && timestamp != null
                        && timestamp.toInstant().equals(block.header().timestamp())
                        && result.getLong("nonce") == block.header().nonce()
                        && result.getInt("difficulty") == block.header().difficulty()
                        && result.getString("transactions_hash").equals(block.header().transactionsHash())
                        && new BigInteger(result.getString("cumulative_work")).equals(block.cumulativeWork());
                if (!same) {
                    throw new PersistenceException("Block hash is already stored with different header data");
                }
                return true;
            }
        }
    }

    private void insertBlock(Connection connection, Block block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_BLOCK)) {
            statement.setString(1, block.hash());
            statement.setLong(2, block.header().height());
            if (block.header().previousHash() == null) {
                statement.setNull(3, Types.CHAR);
            } else {
                statement.setString(3, block.header().previousHash());
            }
            statement.setTimestamp(4, Timestamp.from(block.header().timestamp()), utcCalendar());
            statement.setLong(5, block.header().nonce());
            statement.setInt(6, block.header().difficulty());
            statement.setString(7, block.header().transactionsHash());
            statement.setBigDecimal(8, new BigDecimal(block.cumulativeWork()));
            statement.executeUpdate();
        }
    }

    private void insertBlockTransactions(Connection connection, Block block) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(INSERT_BLOCK_TRANSACTION)) {
            for (int index = 0; index < block.transactionIds().size(); index++) {
                statement.setString(1, block.hash());
                statement.setString(2, block.transactionIds().get(index));
                statement.setInt(3, index);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private List<String> findBlockTransactions(Connection connection, String blockHash) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_BLOCK_TRANSACTIONS)) {
            statement.setString(1, blockHash);
            try (ResultSet result = statement.executeQuery()) {
                List<String> transactionIds = new ArrayList<>();
                while (result.next()) {
                    transactionIds.add(result.getString("tx_id"));
                }
                return List.copyOf(transactionIds);
            }
        }
    }

    private void insertOrVerifyTransaction(Connection connection, LedgerTransaction transaction)
            throws SQLException {
        EncodedTransaction encoded = encodeTransaction(transaction);
        try (PreparedStatement statement = connection.prepareStatement(FIND_TRANSACTION)) {
            statement.setString(1, transaction.transactionId());
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    if (!transaction.eventId().equals(result.getString("event_id"))
                            || !transaction.transactionType().equals(result.getString("tx_type"))
                            || !encoded.payloadHash().equals(result.getString("payload_hash"))) {
                        throw new PersistenceException(
                                "Transaction ID is already stored with a different event or payload");
                    }
                    return;
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(INSERT_TRANSACTION)) {
            statement.setString(1, transaction.transactionId());
            statement.setString(2, transaction.eventId());
            statement.setString(3, transaction.transactionType());
            statement.setString(4, encoded.payload());
            statement.setString(5, encoded.payloadHash());
            statement.setString(6, encoded.signatures());
            statement.setTimestamp(7, Timestamp.from(clock.instant()), utcCalendar());
            statement.executeUpdate();
        }
    }

    private EncodedTransaction encodeTransaction(LedgerTransaction transaction) {
        if (transaction instanceof BatchEvent event) {
            String payloadHash = TransactionCodec.payloadHash(networkId, event);
            String transactionId = TransactionCodec.transactionId(networkId, event);
            if (!transactionId.equals(event.transactionId())) {
                throw new IllegalArgumentException("Batch transaction ID does not match its canonical envelope");
            }
            return new EncodedTransaction(
                    TransactionCodec.payloadJson(networkId, event),
                    payloadHash,
                    TransactionCodec.signaturesJson(event));
        }
        if (transaction instanceof GovernanceTransaction event) {
            String payloadHash = GovernanceCodec.payloadHash(networkId, event);
            String transactionId = GovernanceCodec.transactionId(networkId, event);
            if (!transactionId.equals(event.transactionId())) {
                throw new IllegalArgumentException(
                        "Governance transaction ID does not match its canonical envelope");
            }
            return new EncodedTransaction(
                    GovernanceCodec.payloadJson(networkId, event),
                    payloadHash,
                    GovernanceCodec.signaturesJson(event));
        }
        throw new IllegalArgumentException("Unsupported transaction type");
    }

    private void ensurePendingStatus(Connection connection, String transactionId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ENSURE_PENDING_STATUS)) {
            statement.setString(1, transactionId);
            statement.executeUpdate();
        }
    }

    private CanonicalTip findCanonicalTip(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_CANONICAL_TIP);
             ResultSet result = statement.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            return new CanonicalTip(
                    result.getString("block_hash"),
                    new BigInteger(result.getString("cumulative_work")));
        }
    }

    private List<String> canonicalBranch(Connection connection, Block tip) throws SQLException {
        List<String> branch = new ArrayList<>();
        String currentHash = tip.hash();
        long expectedHeight = tip.header().height();
        while (currentHash != null) {
            try (PreparedStatement statement = connection.prepareStatement(FIND_PATH_BLOCK)) {
                statement.setString(1, currentHash);
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) {
                        throw new PersistenceException("Canonical candidate branch has a missing ancestor");
                    }
                    long storedHeight = result.getLong("height");
                    if (storedHeight != expectedHeight) {
                        throw new PersistenceException("Canonical candidate branch has inconsistent heights");
                    }
                    branch.add(currentHash);
                    currentHash = result.getString("previous_hash");
                    if (storedHeight == 0) {
                        if (currentHash != null || !expectedGenesisHash.equals(branch.get(branch.size() - 1))) {
                            throw new PersistenceException("Canonical candidate branch has a wrong genesis block");
                        }
                    } else {
                        if (currentHash == null || storedHeight == 0) {
                            throw new PersistenceException("Canonical candidate branch terminates before genesis");
                        }
                        expectedHeight = storedHeight - 1;
                    }
                }
            }
        }
        java.util.Collections.reverse(branch);
        return List.copyOf(branch);
    }

    private List<String> transactionOrder(Connection connection, List<String> branch) throws SQLException {
        List<String> ordered = new ArrayList<>();
        for (String blockHash : branch) {
            try (PreparedStatement statement = connection.prepareStatement(FIND_BLOCK_TRANSACTIONS_FOR_PATH)) {
                statement.setString(1, blockHash);
                try (ResultSet result = statement.executeQuery()) {
                    while (result.next()) {
                        ordered.add(result.getString("tx_id"));
                    }
                }
            }
        }
        if (new HashSet<>(ordered).size() != ordered.size()) {
            throw new PersistenceException("Canonical branch contains duplicate transaction references");
        }
        return List.copyOf(ordered);
    }

    private List<String> canonicalTransactionIds(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FIND_CANONICAL_TRANSACTION_IDS);
             ResultSet result = statement.executeQuery()) {
            List<String> transactionIds = new ArrayList<>();
            while (result.next()) {
                transactionIds.add(result.getString("tx_id"));
            }
            if (new HashSet<>(transactionIds).size() != transactionIds.size()) {
                throw new PersistenceException("Current canonical chain contains duplicate transaction references");
            }
            return List.copyOf(transactionIds);
        }
    }

    private void replaceCanonicalFlags(Connection connection, List<String> branch) throws SQLException {
        try (PreparedStatement reset = connection.prepareStatement(RESET_CANONICAL)) {
            reset.executeUpdate();
        }
        try (PreparedStatement mark = connection.prepareStatement(MARK_CANONICAL)) {
            for (String blockHash : branch) {
                mark.setString(1, blockHash);
                if (mark.executeUpdate() != 1) {
                    throw new PersistenceException("Canonical branch contains a block that was not stored");
                }
            }
        }
    }

    private void updateTransactionStatuses(
            Connection connection,
            List<String> newCanonicalTransactions,
            List<String> oldCanonicalTransactions
    ) throws SQLException {
        Set<String> newCanonicalSet = Set.copyOf(newCanonicalTransactions);
        for (String transactionId : oldCanonicalTransactions) {
            if (!newCanonicalSet.contains(transactionId)) {
                try (PreparedStatement statement = connection.prepareStatement(MARK_PENDING)) {
                    statement.setString(1, transactionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(ENSURE_POOL_ENTRY)) {
                    statement.setString(1, transactionId);
                    statement.executeUpdate();
                }
            }
        }
        for (String transactionId : newCanonicalTransactions) {
            try (PreparedStatement statement = connection.prepareStatement(MARK_CONFIRMED)) {
                statement.setString(1, transactionId);
                if (statement.executeUpdate() != 1) {
                    throw new PersistenceException("Canonical transaction has no local status row");
                }
            }
            try (PreparedStatement statement = connection.prepareStatement(REMOVE_POOL_ENTRY)) {
                statement.setString(1, transactionId);
                statement.executeUpdate();
            }
        }
    }

    private Calendar utcCalendar() {
        return Calendar.getInstance(TimeZone.getTimeZone("UTC"));
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

    private record CanonicalTip(String hash, BigInteger cumulativeWork) {
    }

    private record EncodedTransaction(
            String payload,
            String payloadHash,
            String signatures
    ) {
    }

}
