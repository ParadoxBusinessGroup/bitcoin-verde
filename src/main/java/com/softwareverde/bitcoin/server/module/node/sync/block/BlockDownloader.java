package com.softwareverde.bitcoin.server.module.node.sync.block;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.block.header.BlockHeader;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.DatabaseConnectionFactory;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.module.node.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.BlockHeaderDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.PendingBlockDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.manager.BitcoinNodeDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.manager.BitcoinNodeManager;
import com.softwareverde.bitcoin.server.module.node.sync.block.pending.PendingBlockId;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.concurrent.service.SleepyService;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.util.TransactionUtil;
import com.softwareverde.io.Logger;
import com.softwareverde.network.p2p.node.NodeId;
import com.softwareverde.util.Util;
import com.softwareverde.util.timer.MilliTimer;
import com.softwareverde.util.type.time.SystemTime;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BlockDownloader extends SleepyService {
    public static final Integer MAX_DOWNLOAD_FAILURE_COUNT = 10;

    protected static final Long MAX_TIMEOUT = 90000L;

    protected final Object _downloadCallbackPin = new Object();

    protected final SystemTime _systemTime = new SystemTime();
    protected final BitcoinNodeManager _bitcoinNodeManager;
    protected final DatabaseConnectionFactory _databaseConnectionFactory;
    protected final DatabaseManagerCache _databaseCache;
    protected final Map<Sha256Hash, MilliTimer> _currentBlockDownloadSet = new ConcurrentHashMap<>();
    protected final BitcoinNodeManager.DownloadBlockCallback _blockDownloadedCallback;

    protected Runnable _newBlockAvailableCallback = null;

    protected Boolean _hasGenesisBlock = false;
    protected Long _lastGenesisDownloadTimestamp = null;

    protected void _onBlockDownloaded(final Block block, final DatabaseConnection databaseConnection) throws DatabaseException {
        final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);

        pendingBlockDatabaseManager.storeBlock(block);
    }

    protected void _markPendingBlockIdsAsFailed(final Set<Sha256Hash> pendingBlockHashes) {
        try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);
            for (final Sha256Hash pendingBlockHash : pendingBlockHashes) {
                final PendingBlockId pendingBlockId = pendingBlockDatabaseManager.getPendingBlockId(pendingBlockHash);
                if (pendingBlockId == null) { continue; }

                pendingBlockDatabaseManager.incrementFailedDownloadCount(pendingBlockId);
            }
            pendingBlockDatabaseManager.purgeFailedPendingBlocks(MAX_DOWNLOAD_FAILURE_COUNT);
        }
        catch (final DatabaseException exception) {
            Logger.log(exception);
        }
    }

    // This function iterates through each Block in-flight, and checks for items that have exceeded the MAX_TIMEOUT.
    //  Items exceeding the timeout have their onFailure method called.
    //  This function should not be necessary, and is a work-around for a bug within the NodeManager that is causing onFailure to not be triggered.
    // TODO: Investigate why onFailure is not being invoked by the BitcoinNodeManager.
    protected void _checkForStalledDownloads() {
        final MutableList<Sha256Hash> stalledBlockHashes = new MutableList<>();
        for (final Sha256Hash blockHash : _currentBlockDownloadSet.keySet()) {
            final MilliTimer milliTimer = _currentBlockDownloadSet.get(blockHash);
            if (milliTimer == null) {
                stalledBlockHashes.add(blockHash);
                continue;
            }

            milliTimer.stop();
            final Long msElapsed = milliTimer.getMillisecondsElapsed();
            if (msElapsed >= MAX_TIMEOUT) {
                stalledBlockHashes.add(blockHash);
            }
        }

        for (final Sha256Hash stalledBlockHash : stalledBlockHashes) {
            Logger.log("Stalled Block Detected: " + stalledBlockHash);
            _currentBlockDownloadSet.remove(stalledBlockHash);
            _blockDownloadedCallback.onFailure(stalledBlockHash);
        }
    }

    @Override
    protected void _onStart() { }

    @Override
    protected Boolean _run() {
        final Integer maximumConcurrentDownloadCount = Math.min(21, ((_bitcoinNodeManager.getActiveNodeCount() * 3) + 1) );

        _checkForStalledDownloads();

        { // Determine if routine should wait for a request to complete...
            Integer currentDownloadCount = _currentBlockDownloadSet.size();
            while (currentDownloadCount >= maximumConcurrentDownloadCount) {
                synchronized (_downloadCallbackPin) {
                    final MilliTimer waitTimer = new MilliTimer();
                    try {
                        waitTimer.start();
                        _downloadCallbackPin.wait(MAX_TIMEOUT);
                        waitTimer.stop();
                    }
                    catch (final InterruptedException exception) { return false; }

                    if (waitTimer.getMillisecondsElapsed() >= MAX_TIMEOUT) {
                        Logger.log("NOTICE: Block download stalled.");

                        _markPendingBlockIdsAsFailed(_currentBlockDownloadSet.keySet());
                        _currentBlockDownloadSet.clear();
                        return false;
                    }
                }

                currentDownloadCount = _currentBlockDownloadSet.size();
            }
        }

        try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            if (! _hasGenesisBlock) { // Since nodes do not advertise inventory of the genesis block, specifically add it if it is required...
                final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(databaseConnection, _databaseCache);
                final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection, _databaseCache);
                final BlockId genesisBlockId = blockHeaderDatabaseManager.getBlockHeaderId(BlockHeader.GENESIS_BLOCK_HASH);
                if ( (genesisBlockId == null) || (! blockDatabaseManager.hasTransactions(genesisBlockId)) ) {
                    final Long now =_systemTime.getCurrentTimeInSeconds();
                    final Long secondsSinceLastDownloadAttempt = (now - Util.coalesce(_lastGenesisDownloadTimestamp));

                    if (secondsSinceLastDownloadAttempt > 30) {
                        _lastGenesisDownloadTimestamp = _systemTime.getCurrentTimeInSeconds();
                        _bitcoinNodeManager.requestBlock(BlockHeader.GENESIS_BLOCK_HASH, _blockDownloadedCallback);
                    }
                }
                else {
                    _hasGenesisBlock = true;
                }
            }

            final List<BitcoinNode> nodes = _bitcoinNodeManager.getNodes();

            final BitcoinNodeDatabaseManager nodeDatabaseManager = new BitcoinNodeDatabaseManager(databaseConnection);
            final HashMap<NodeId, BitcoinNode> nodeMap = new HashMap<>();
            final List<NodeId> nodeIds;
            {
                final ImmutableListBuilder<NodeId> listBuilder = new ImmutableListBuilder<>(nodes.getSize());
                for (final BitcoinNode node : nodes) {
                    final NodeId nodeId = nodeDatabaseManager.getNodeId(node);
                    if (nodeId != null) {
                        listBuilder.add(nodeId);
                        nodeMap.put(nodeId, node);
                    }
                }
                nodeIds = listBuilder.build();
            }

            final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);
            try {
                TransactionUtil.startTransaction(databaseConnection);
                pendingBlockDatabaseManager.cleanupPendingBlocks();
                TransactionUtil.commitTransaction(databaseConnection);
            }
            catch (final DatabaseException exception) {
                Logger.log("Unable to cleanup pending blocks..."); // Often encounters SQL deadlock...
            }

            final Map<PendingBlockId, NodeId> downloadPlan = pendingBlockDatabaseManager.selectIncompletePendingBlocks(nodeIds, maximumConcurrentDownloadCount * 2);
            if (downloadPlan.isEmpty()) { return false; }

            for (final PendingBlockId pendingBlockId : downloadPlan.keySet()) {
                if (_currentBlockDownloadSet.size() >= maximumConcurrentDownloadCount) { break; }

                final Sha256Hash blockHash = pendingBlockDatabaseManager.getPendingBlockHash(pendingBlockId);
                if (blockHash == null) { continue; }

                final Boolean itemIsAlreadyBeingDownloaded = _currentBlockDownloadSet.containsKey(blockHash);
                if (itemIsAlreadyBeingDownloaded) { continue; }

                final NodeId nodeId = downloadPlan.get(pendingBlockId);
                final BitcoinNode bitcoinNode = nodeMap.get(nodeId);

                final MilliTimer timer = new MilliTimer();
                timer.start();

                _currentBlockDownloadSet.put(blockHash, timer);

                // if (bitcoinNode.supportsExtraThinBlocks() && _synchronizationStatus.isReadyForTransactions()) {
                //     _bitcoinNodeManager.requestThinBlock(bitcoinNode, blockHash, _blockDownloadedCallback);
                // }
                // else {
                //     _bitcoinNodeManager.requestBlock(bitcoinNode, blockHash, _blockDownloadedCallback);
                // }
                _bitcoinNodeManager.requestBlock(bitcoinNode, blockHash, _blockDownloadedCallback);

                pendingBlockDatabaseManager.updateLastDownloadAttemptTime(pendingBlockId);
            }
        }
        catch (final DatabaseException exception) {
            Logger.log(exception);
            return false;
        }

        return true;
    }

    @Override
    protected void _onSleep() {
        final List<NodeId> connectedNodeIds = _bitcoinNodeManager.getNodeIds();
        if (! connectedNodeIds.isEmpty()) {

            _bitcoinNodeManager.findNodeInventory();

            Logger.log("Searching for Unlocatable Pending Blocks...");
            try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
                final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);
                pendingBlockDatabaseManager.purgeUnlocatablePendingBlocks(connectedNodeIds);
            }
            catch (final DatabaseException exception) {
                Logger.log(exception);
            }
        }
    }

    public BlockDownloader(final BitcoinNodeManager bitcoinNodeManager, final DatabaseConnectionFactory databaseConnectionFactory, final DatabaseManagerCache databaseCache) {
        _bitcoinNodeManager = bitcoinNodeManager;
        _databaseConnectionFactory = databaseConnectionFactory;
        _databaseCache = databaseCache;

        _blockDownloadedCallback = new BitcoinNodeManager.DownloadBlockCallback() {
            @Override
            public void onResult(final Block block) {

                final Sha256Hash blockHash = block.getHash();
                final MilliTimer timer = _currentBlockDownloadSet.remove(blockHash);
                if (timer != null) {
                    timer.stop();
                }

                Logger.log("Downloaded Block: " + blockHash + " (" + (timer != null ? timer.getMillisecondsElapsed() : "??") + "ms)");

                try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
                    _onBlockDownloaded(block, databaseConnection);
                }
                catch (final DatabaseException exception) {
                    Logger.log(exception);
                    return;
                }
                finally {
                    synchronized (_downloadCallbackPin) {
                        _downloadCallbackPin.notifyAll();
                    }
                }

                final Runnable newBlockAvailableCallback = _newBlockAvailableCallback;
                if (newBlockAvailableCallback != null) {
                    newBlockAvailableCallback.run();
                }
            }

            @Override
            public void onFailure(final Sha256Hash blockHash) {
                try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
                    final PendingBlockDatabaseManager pendingBlockDatabaseManager = new PendingBlockDatabaseManager(databaseConnection);

                    final PendingBlockId pendingBlockId = pendingBlockDatabaseManager.getPendingBlockId(blockHash);
                    if (pendingBlockId == null) {
                        Logger.log("Unable to increment download failure count for block: " + blockHash);
                        return;
                    }

                    pendingBlockDatabaseManager.incrementFailedDownloadCount(pendingBlockId);
                    pendingBlockDatabaseManager.purgeFailedPendingBlocks(MAX_DOWNLOAD_FAILURE_COUNT);
                }
                catch (final DatabaseException exception) {
                    Logger.log(exception);
                    Logger.log("Unable to increment download failure count for block: " + blockHash);
                }
                finally {
                    _currentBlockDownloadSet.remove(blockHash);

                    synchronized (_downloadCallbackPin) {
                        _downloadCallbackPin.notifyAll();
                    }
                }
            }
        };
    }

    public void setNewBlockAvailableCallback(final Runnable runnable) {
        _newBlockAvailableCallback = runnable;
    }

    public void submitBlock(final Block block) {
        try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            _onBlockDownloaded(block, databaseConnection);
            Logger.log("Block submitted: " + block.getHash());
        }
        catch (final DatabaseException exception) {
            Logger.log(exception);
            return;
        }

        final Runnable newBlockAvailableCallback = _newBlockAvailableCallback;
        if (newBlockAvailableCallback != null) {
            newBlockAvailableCallback.run();
        }
    }

}
