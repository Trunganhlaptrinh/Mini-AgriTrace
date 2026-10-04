package config;

import blockchain.BlockProducer;
import blockchain.BlockValidationContext;
import blockchain.BlockValidator;
import blockchain.Blockchain;
import blockchain.GenesisBlockFactory;
import blockchain.TransactionPool;
import dal.BlockDAO;
import dal.NetworkConfigDAO;
import dal.TransactionDAO;
import dal.TransactionStatusDAO;
import dal.ShipmentProposalDAO;
import jakarta.servlet.ServletContextEvent;
import jakarta.servlet.ServletContextListener;
import jakarta.servlet.annotation.WebListener;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import model.Block;
import service.BatchService;
import service.GovernanceService;
import service.NodeRuntime;
import service.TransactionService;
import service.TraceabilityService;
import service.ShipmentProposalService;
import service.PeerLedgerService;
import network.PeerAuthenticator;
import network.PeerClient;
import network.PeerIdentity;
import network.PeerLedgerSynchronizer;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@WebListener
public final class NodeRuntimeListener implements ServletContextListener {
    private static final Logger LOGGER = Logger.getLogger(NodeRuntimeListener.class.getName());
    private ScheduledExecutorService peerSyncExecutor;

    @Override
    public void contextInitialized(ServletContextEvent event) {
        var context = event.getServletContext();
        try {
            NetworkConfiguration configuration = new NetworkConfigDAO().loadRequired();
            Block genesis = GenesisBlockFactory.configuredGenesis(configuration);
            BlockDAO blockDAO = new BlockDAO(
                    configuration.networkId(), configuration.genesisHash());
            BlockValidator validator = new BlockValidator(
                    configuration.networkId(),
                    configuration.initialPowDifficulty(),
                    configuration.genesisHash(),
                    configuration.genesisAdminPublicKeyBytes());
            Blockchain blockchain = new Blockchain(
                    validator, blockDAO, BlockValidationContext.genesis(Map.of(), Map.of()));
            if (blockchain.loadCanonicalState().tip() == null) {
                blockchain.processBlock(genesis, List.of());
            }

            TransactionDAO transactionDAO = new TransactionDAO();
            TransactionPool transactionPool = new TransactionPool(
                    configuration.networkId(), null, transactionDAO, blockchain);
            BlockProducer blockProducer = new BlockProducer(
                    configuration.networkId(),
                    configuration.initialPowDifficulty(),
                    Clock.systemUTC(),
                    blockchain,
                    transactionPool);
            TransactionService transactionService = new TransactionService(
                    transactionPool, new TransactionStatusDAO(), blockProducer);
            PeerIdentity peerIdentity = PeerIdentity.loadRequired(
                    PeerIdentityConfiguration.loadRequired(), blockchain);
            PeerAuthenticator peerAuthenticator = new PeerAuthenticator(blockchain);
            GovernanceService governanceService = new GovernanceService(
                    configuration, transactionService);
            BatchService batchService = new BatchService(transactionService);
            TraceabilityService traceabilityService = new TraceabilityService(blockchain);
            PeerClient peerClient = new PeerClient(blockchain, peerIdentity);
            ShipmentProposalService shipmentProposalService = new ShipmentProposalService(
                    configuration.networkId(),
                    blockchain,
                    new ShipmentProposalDAO(configuration.networkId()),
                    transactionService,
                    peerClient,
                    peerClient);
            PeerLedgerService peerLedgerService = new PeerLedgerService(
                    configuration.networkId(), blockchain, transactionService);
            PeerLedgerSynchronizer peerLedgerSynchronizer =
                    new PeerLedgerSynchronizer(blockchain, peerLedgerService, peerIdentity);
            context.setAttribute(
                    NodeRuntime.SERVLET_CONTEXT_ATTRIBUTE,
                    new NodeRuntime(
                            transactionService, governanceService, batchService, traceabilityService,
                            shipmentProposalService, peerLedgerService, peerIdentity, peerAuthenticator));
            peerSyncExecutor = Executors.newSingleThreadScheduledExecutor(task -> {
                Thread thread = new Thread(task, "agritrace-peer-ledger-sync");
                thread.setDaemon(true);
                return thread;
            });
            peerSyncExecutor.scheduleWithFixedDelay(
                    () -> {
                        try {
                            peerLedgerSynchronizer.synchronizeWithActivePeers();
                        } catch (RuntimeException exception) {
                            LOGGER.log(Level.SEVERE, "Scheduled peer ledger synchronization failed", exception);
                        }
                    },
                    10,
                    30,
                    TimeUnit.SECONDS);
        } catch (RuntimeException exception) {
            context.log("AgriTrace blockchain runtime initialization failed", exception);
            throw new IllegalStateException("AgriTrace runtime could not be initialized", exception);
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        if (peerSyncExecutor != null) {
            peerSyncExecutor.shutdownNow();
        }
    }
}
