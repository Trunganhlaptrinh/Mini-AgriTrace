package service;

import blockchain.BlockProducer;
import blockchain.BlockRepository;
import blockchain.BlockValidationContext;
import blockchain.BlockValidationResult;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.PendingTransactionSource;
import blockchain.ProofOfWork;
import blockchain.TransactionPool;
import dal.TransactionDAO;
import dal.TransactionStatusDAO;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import model.Block;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PeerLedgerServiceTest {
    private static final String NETWORK_ID = "peer-ledger-service-test";
    private static final Instant START = Instant.parse("2026-10-05T00:00:00.000Z");

    @Test
    void providesSparseOrderedLocatorAndNextCanonicalBlock() {
        InMemoryBlockRepository repository = new InMemoryBlockRepository();
        Block genesis = ProofOfWork.mine(
                NETWORK_ID, 0, null, START, 1, List.of(), BigInteger.ZERO);
        Blockchain blockchain = new Blockchain(
                new BlockValidator(NETWORK_ID, 1, genesis.hash()),
                repository,
                BlockValidationContext.genesis(Map.of(), Map.of()));
        blockchain.processBlock(genesis, List.of());
        Block parent = genesis;
        for (int height = 1; height <= 6; height++) {
            Block child = ProofOfWork.mine(
                    NETWORK_ID,
                    height,
                    parent.hash(),
                    START.plusMillis(height),
                    1,
                    List.of(),
                    parent.cumulativeWork());
            blockchain.processBlock(child, List.of());
            parent = child;
        }
        PeerLedgerService service = new PeerLedgerService(
                NETWORK_ID, blockchain, unusedTransactionService(blockchain));

        List<PeerLedgerService.BlockLocatorEntry> locator = service.locator();

        assertEquals(0, locator.get(0).height());
        assertEquals(genesis.hash(), locator.get(0).hash());
        assertEquals(6, locator.get(locator.size() - 1).height());
        assertEquals(parent.hash(), locator.get(locator.size() - 1).hash());
        assertEquals(
                List.of(0L, 3L, 5L, 6L),
                locator.stream().map(PeerLedgerService.BlockLocatorEntry::height).toList());
        assertEquals(
                service.canonicalBlocks().get(1),
                service.nextCanonicalBlock(genesis.hash()));
        assertNull(service.nextCanonicalBlock(parent.hash()));
        assertEquals(parent, service.findCanonicalBlock(parent.hash()).block());
        assertThrows(IllegalArgumentException.class,
                () -> service.nextCanonicalBlock("f".repeat(64)));
    }

    private TransactionService unusedTransactionService(Blockchain blockchain) {
        TransactionDAO transactionDAO = new TransactionDAO(
                () -> { throw new SQLException("This test does not access a database"); },
                Clock.fixed(START, ZoneOffset.UTC));
        TransactionPool transactionPool = new TransactionPool(
                NETWORK_ID, null, transactionDAO, blockchain);
        TransactionStatusDAO statusDAO = new TransactionStatusDAO(
                () -> { throw new SQLException("This test does not access a database"); });
        BlockProducer blockProducer = new BlockProducer(
                NETWORK_ID,
                1,
                Clock.fixed(START.plusSeconds(1), ZoneOffset.UTC),
                blockchain,
                () -> new PendingTransactionSource.Selection(List.of(), Map.of()));
        return new TransactionService(transactionPool, statusDAO, blockProducer);
    }

    private static final class InMemoryBlockRepository implements BlockRepository {
        private final Map<String, StoredBlock> byHash = new HashMap<>();
        private final List<StoredBlock> canonical = new ArrayList<>();

        @Override
        public StoreResult storeValidatedBlock(BlockValidationResult validation) {
            Block block = validation.block();
            StoredBlock stored = new StoredBlock(block, List.of());
            if (byHash.putIfAbsent(block.hash(), stored) != null) {
                return StoreResult.ALREADY_PRESENT;
            }
            canonical.add(stored);
            return StoreResult.CANONICAL_TIP_UPDATED;
        }

        @Override
        public List<StoredBlock> loadCanonicalChain() {
            return List.copyOf(canonical);
        }

        @Override
        public List<StoredBlock> loadBranch(String tipHash) {
            for (int index = 0; index < canonical.size(); index++) {
                if (canonical.get(index).block().hash().equals(tipHash)) {
                    return List.copyOf(canonical.subList(0, index + 1));
                }
            }
            throw new IllegalArgumentException("Unknown test block");
        }
    }
}
