package service;

import blockchain.BlockProcessingResult;
import blockchain.BlockRepository;
import blockchain.Blockchain;
import dal.TransactionDAO;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import model.Block;
import model.LedgerTransaction;
import model.PeerRegistration;

public final class PeerLedgerService {
    private final String networkId;
    private final Blockchain blockchain;
    private final TransactionService transactionService;

    public PeerLedgerService(
            String networkId,
            Blockchain blockchain,
            TransactionService transactionService
    ) {
        if (networkId == null || networkId.isBlank()) {
            throw new IllegalArgumentException("networkId must not be blank");
        }
        this.networkId = networkId;
        this.blockchain = Objects.requireNonNull(blockchain, "blockchain");
        this.transactionService = Objects.requireNonNull(transactionService, "transactionService");
    }

    public String networkId() {
        return networkId;
    }

    public List<BlockRepository.StoredBlock> canonicalBlocks() {
        return blockchain.loadValidatedCanonicalChainSnapshot().blocks();
    }

    public List<LedgerTransaction> pendingTransactions() {
        return transactionService.pendingTransactions();
    }

    public TransactionDAO.SubmissionResult receiveTransaction(
            PeerRegistration sourcePeer,
            PeerRegistration localPeer,
            LedgerTransaction transaction
    ) {
        requireDistinctActivePeers(sourcePeer, localPeer);
        if (transaction == null) {
            throw new IllegalArgumentException("transaction must not be null");
        }
        return transactionService.submit(transaction);
    }

    public BlockProcessingResult receiveBlock(
            PeerRegistration sourcePeer,
            PeerRegistration localPeer,
            BlockRepository.StoredBlock storedBlock
    ) {
        requireDistinctActivePeers(sourcePeer, localPeer);
        if (storedBlock == null) {
            throw new IllegalArgumentException("storedBlock must not be null");
        }
        List<LedgerTransaction> transactions = new ArrayList<>(storedBlock.transactions());
        return blockchain.processBlock(storedBlock.block(), transactions);
    }

    public BlockRepository.StoredBlock findCanonicalBlock(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("block hash must be a lowercase SHA-256 digest");
        }
        return canonicalBlocks().stream()
                .filter(stored -> stored.block().hash().equals(hash))
                .findFirst()
                .orElse(null);
    }

    public BlockRepository.StoredBlock nextCanonicalBlock(String afterHash) {
        if (afterHash == null || !afterHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("afterHash must be a lowercase block hash");
        }
        List<BlockRepository.StoredBlock> blocks = canonicalBlocks();
        for (int index = 0; index < blocks.size(); index++) {
            if (blocks.get(index).block().hash().equals(afterHash)) {
                return index + 1 < blocks.size() ? blocks.get(index + 1) : null;
            }
        }
        throw new IllegalArgumentException("afterHash is not in the canonical chain");
    }

    public List<BlockLocatorEntry> locator() {
        List<BlockRepository.StoredBlock> blocks = canonicalBlocks();
        if (blocks.isEmpty()) {
            return List.of();
        }
        List<BlockLocatorEntry> selectedDescending = new ArrayList<>();
        int index = blocks.size() - 1;
        long step = 1;
        while (index > 0) {
            Block block = blocks.get(index).block();
            selectedDescending.add(new BlockLocatorEntry(block.header().height(), block.hash()));
            index = (int) Math.max(0, index - step);
            step = Math.min(step * 2, Integer.MAX_VALUE);
        }
        Block genesis = blocks.get(0).block();
        selectedDescending.add(new BlockLocatorEntry(genesis.header().height(), genesis.hash()));
        java.util.Collections.reverse(selectedDescending);
        return List.copyOf(selectedDescending);
    }

    private void requireDistinctActivePeers(PeerRegistration sourcePeer, PeerRegistration localPeer) {
        if (sourcePeer == null || !sourcePeer.active()
                || localPeer == null || !localPeer.active()) {
            throw new AuthenticationException(
                    "PEER_NOT_AUTHORIZED", "Both source and local peers must be active", 403);
        }
        if (sourcePeer.peerId().equals(localPeer.peerId())) {
            throw new AuthenticationException(
                    "PEER_NOT_AUTHORIZED", "A peer cannot synchronize with itself", 403);
        }
    }

    public record BlockLocatorEntry(long height, String hash) {
        public BlockLocatorEntry {
            if (height < 0 || hash == null || !hash.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid block locator entry");
            }
        }
    }
}
