package com.softwareverde.bitcoin.server.module;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.Configuration;
import com.softwareverde.bitcoin.server.Environment;
import com.softwareverde.bitcoin.server.database.BitcoinVerdeDatabase;
import com.softwareverde.bitcoin.server.database.Database;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.DatabaseConnectionFactory;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.database.cache.MasterDatabaseManagerCache;
import com.softwareverde.bitcoin.server.database.cache.ReadOnlyLocalDatabaseManagerCache;
import com.softwareverde.bitcoin.server.message.type.node.address.BitcoinNodeIpAddress;
import com.softwareverde.bitcoin.server.message.type.node.feature.LocalNodeFeatures;
import com.softwareverde.bitcoin.server.message.type.node.feature.NodeFeatures;
import com.softwareverde.bitcoin.server.module.node.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.BlockHeaderDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.handler.BlockInventoryMessageHandler;
import com.softwareverde.bitcoin.server.module.node.handler.RequestDataHandler;
import com.softwareverde.bitcoin.server.module.node.handler.SynchronizationStatusHandler;
import com.softwareverde.bitcoin.server.module.node.handler.block.QueryBlockHeadersHandler;
import com.softwareverde.bitcoin.server.module.node.handler.block.QueryBlocksHandler;
import com.softwareverde.bitcoin.server.module.node.handler.transaction.QueryUnconfirmedTransactionsHandler;
import com.softwareverde.bitcoin.server.module.node.handler.transaction.TransactionInventoryMessageHandlerFactory;
import com.softwareverde.bitcoin.server.module.node.manager.NodeInitializer;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.concurrent.pool.MainThreadPool;
import com.softwareverde.concurrent.pool.ThreadPool;
import com.softwareverde.concurrent.pool.ThreadPoolFactory;
import com.softwareverde.concurrent.pool.ThreadPoolThrottle;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.util.TransactionUtil;
import com.softwareverde.io.Logger;

import java.io.File;

public class RepairModule {
    public static void execute(final String configurationFileName, final String[] blockHashes) {
        final RepairModule repairModule = new RepairModule(configurationFileName, blockHashes);
        repairModule.run();
    }

    protected final Configuration _configuration;
    protected final Environment _environment;
    protected final List<Sha256Hash> _blockHashes;

    protected final NodeInitializer _nodeInitializer;
    protected final MainThreadPool _threadPool = new MainThreadPool(256, 10000L);

    protected Configuration _loadConfigurationFile(final String configurationFilename) {
        final File configurationFile =  new File(configurationFilename);
        if (! configurationFile.isFile()) {
            Logger.error("Invalid configuration file.");
            BitcoinUtil.exitFailure();
        }

        return new Configuration(configurationFile);
    }

    protected RepairModule(final String configurationFilename, final String[] blockHashes) {
        _configuration = _loadConfigurationFile(configurationFilename);

        final ImmutableListBuilder<Sha256Hash> blockHashesBuilder = new ImmutableListBuilder<>(blockHashes.length);
        for (final String blockHashString : blockHashes) {
            final Sha256Hash blockHash = Sha256Hash.fromHexString(blockHashString);
            if (blockHash != null) {
                blockHashesBuilder.add(blockHash);
            }
        }
        _blockHashes = blockHashesBuilder.build();

        final Configuration.BitcoinProperties bitcoinProperties = _configuration.getBitcoinProperties();
        final Configuration.DatabaseProperties databaseProperties = bitcoinProperties.getDatabaseProperties();

        final Database database = BitcoinVerdeDatabase.newInstance(BitcoinVerdeDatabase.BITCOIN, databaseProperties);
        if (database == null) {
            Logger.log("Error initializing database.");
            BitcoinUtil.exitFailure();
        }
        Logger.log("[Database Online]");

        final Long maxUtxoCacheByteCount = bitcoinProperties.getMaxUtxoCacheByteCount();
        final MasterDatabaseManagerCache masterDatabaseManagerCache = new MasterDatabaseManagerCache(maxUtxoCacheByteCount);
        _environment = new Environment(database, masterDatabaseManagerCache);

        final DatabaseManagerCache databaseManagerCache = new ReadOnlyLocalDatabaseManagerCache(masterDatabaseManagerCache);

        final LocalNodeFeatures localNodeFeatures = new LocalNodeFeatures() {
            @Override
            public NodeFeatures getNodeFeatures() {
                final NodeFeatures nodeFeatures = new NodeFeatures();
                nodeFeatures.enableFeature(NodeFeatures.Feature.BITCOIN_CASH_ENABLED);
                return nodeFeatures;
            }
        };

        { // Initialize NodeInitializer...
            final DatabaseConnectionFactory databaseConnectionFactory = database.newConnectionFactory();

            final NodeInitializer.Properties nodeInitializerProperties = new NodeInitializer.Properties();
            nodeInitializerProperties.synchronizationStatus = new SynchronizationStatusHandler(databaseConnectionFactory, databaseManagerCache);
            nodeInitializerProperties.queryBlocksCallback = QueryBlocksHandler.IGNORE_REQUESTS_HANDLER;
            nodeInitializerProperties.queryBlockHeadersCallback = QueryBlockHeadersHandler.IGNORES_REQUESTS_HANDLER;
            nodeInitializerProperties.requestDataCallback = RequestDataHandler.IGNORE_REQUESTS_HANDLER;
            nodeInitializerProperties.transactionsAnnouncementCallbackFactory = TransactionInventoryMessageHandlerFactory.IGNORE_NEW_TRANSACTIONS_HANDLER_FACTORY;
            nodeInitializerProperties.blockInventoryMessageHandler = BlockInventoryMessageHandler.IGNORE_INVENTORY_HANDLER;
            nodeInitializerProperties.queryUnconfirmedTransactionsCallback = QueryUnconfirmedTransactionsHandler.IGNORE_REQUESTS_HANDLER;
            nodeInitializerProperties.requestPeersHandler = new BitcoinNode.RequestPeersHandler() {
                @Override
                public List<BitcoinNodeIpAddress> getConnectedPeers() {
                    return new MutableList<>(0);
                }
            };

            final ThreadPoolFactory threadPoolFactory = new ThreadPoolFactory() {
                @Override
                public ThreadPool newThreadPool() {
                    return new ThreadPoolThrottle(bitcoinProperties.getMaxMessagesPerSecond(), _threadPool);
                }
            };

            nodeInitializerProperties.threadPoolFactory = threadPoolFactory;
            nodeInitializerProperties.localNodeFeatures = localNodeFeatures;

            _nodeInitializer = new NodeInitializer(nodeInitializerProperties);
        }
    }

    public void run() {
        final Database database = _environment.getDatabase();
        final MasterDatabaseManagerCache masterDatabaseManagerCache = _environment.getMasterDatabaseManagerCache();

        final DatabaseManagerCache databaseManagerCache = new ReadOnlyLocalDatabaseManagerCache(masterDatabaseManagerCache);

        final List<BitcoinNode> bitcoinNodes;
        {
            final Configuration.BitcoinProperties bitcoinProperties = _configuration.getBitcoinProperties();
            final ImmutableListBuilder<BitcoinNode> bitcoinNodeListBuilder = new ImmutableListBuilder<>();
            for (final Configuration.SeedNodeProperties seedNodeProperties : bitcoinProperties.getSeedNodeProperties()) {
                final String host = seedNodeProperties.getAddress();
                final Integer port = seedNodeProperties.getPort();

                final BitcoinNode bitcoinNode = _nodeInitializer.initializeNode(host, port);
                bitcoinNodeListBuilder.add(bitcoinNode);
            }
            bitcoinNodes = bitcoinNodeListBuilder.build();

            if (bitcoinNodes.isEmpty()) {
                Logger.log("ERROR: No trusted nodes set.");
                BitcoinUtil.exitFailure();
            }
        }

        final BitcoinNode bitcoinNode = bitcoinNodes.get(0);

        for (final Sha256Hash blockHash : _blockHashes) {
            final Object synchronizer = new Object();
            bitcoinNode.requestBlock(blockHash, new BitcoinNode.DownloadBlockCallback() {
                @Override
                public void onResult(final Block block) {
                    try (final DatabaseConnection databaseConnection = database.newConnection()) {
                        final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(databaseConnection, databaseManagerCache);
                        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection, databaseManagerCache);

                        final BlockId blockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);
                        if (blockId == null) {
                            Logger.log("Block not found: " + blockHash);
                            return;
                        }

                        TransactionUtil.startTransaction(databaseConnection);
                        blockDatabaseManager.repairBlock(block);
                        TransactionUtil.commitTransaction(databaseConnection);

                        Logger.log("Repaired block: " + blockHash);

                    }
                    catch (final DatabaseException exception) {
                        Logger.log(exception);
                        Logger.log("Error repairing block: " + blockHash);
                    }

                    synchronizer.notifyAll();
                }
            });

            try { synchronizer.wait(); }
            catch (final InterruptedException exception) { break; }
        }

        _environment.getMasterDatabaseManagerCache().close();
        System.exit(0);
    }
}
