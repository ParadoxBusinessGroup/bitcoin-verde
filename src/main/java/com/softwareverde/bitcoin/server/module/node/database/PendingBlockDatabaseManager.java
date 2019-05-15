package com.softwareverde.bitcoin.server.module.node.database;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockDeflater;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.cache.DisabledDatabaseManagerCache;
import com.softwareverde.bitcoin.server.module.node.sync.block.pending.PendingBlock;
import com.softwareverde.bitcoin.server.module.node.sync.block.pending.PendingBlockId;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.util.DatabaseUtil;
import com.softwareverde.io.Logger;
import com.softwareverde.network.p2p.node.NodeId;
import com.softwareverde.util.Tuple;
import com.softwareverde.util.Util;
import com.softwareverde.util.type.time.SystemTime;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PendingBlockDatabaseManager {
    public static final ReentrantReadWriteLock.ReadLock READ_LOCK;
    public static final ReentrantReadWriteLock.WriteLock WRITE_LOCK;
    static {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        // NOTE: Read/Write locks are currently disabled.
        //  Enabling the locks greatly slows down block download and processing, and
        //  there is no clear detriment to keeping the locks disabled.
        READ_LOCK = new DisabledReadLock(readWriteLock); // readWriteLock.readLock();
        WRITE_LOCK = new DisabledWriteLock(readWriteLock); // readWriteLock.writeLock();
    }

    protected final SystemTime _systemTime = new SystemTime();
    protected final DatabaseConnection _databaseConnection;

    protected PendingBlockId _getPendingBlockId(final Sha256Hash blockHash) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM pending_blocks WHERE hash = ?")
                .setParameter(blockHash)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return PendingBlockId.wrap(row.getLong("id"));
    }

    protected List<PendingBlockId> _getPendingBlockIdsWithPreviousBlockHash(final Sha256Hash blockHash) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM pending_blocks WHERE previous_block_hash = ?")
                .setParameter(blockHash)
        );
        if (rows.isEmpty()) { return new MutableList<>(); }

        final ImmutableListBuilder<PendingBlockId> listBuilder = new ImmutableListBuilder<>(rows.size());
        for (final Row row : rows) {
            final PendingBlockId pendingBlockId = PendingBlockId.wrap(row.getLong("id"));
            listBuilder.add(pendingBlockId);
        }
        return listBuilder.build();
    }

    protected PendingBlockId _storePendingBlock(final Sha256Hash blockHash, final Sha256Hash previousBlockHash) throws DatabaseException {
        final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
        final Long priority;
        {
            final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(_databaseConnection, new DisabledDatabaseManagerCache());
            final BlockId blockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);
            if (blockId != null) {
                priority = blockHeaderDatabaseManager.getBlockTimestamp(blockId);
            }
            else {
                priority = currentTimestamp;
            }
        }

        final Long pendingBlockId = _databaseConnection.executeSql(
            new Query("INSERT IGNORE INTO pending_blocks (hash, previous_block_hash, timestamp, priority) VALUES (?, ?, ?, ?)")
                .setParameter(blockHash)
                .setParameter(previousBlockHash)
                .setParameter(currentTimestamp)
                .setParameter(priority)
        );

        if (pendingBlockId == 0) {
            // The insert was ignored, so return the existing row.  This logic is necessary to prevent a race condition due to PendingBlockDatabaseManager not locking...
            final PendingBlockId existingPendingBlockId = _getPendingBlockId(blockHash);
            if (previousBlockHash != null) {
                _updatePendingBlock(existingPendingBlockId, previousBlockHash);
            }
            return existingPendingBlockId;
        }

        return PendingBlockId.wrap(pendingBlockId);
    }

    protected void _updatePendingBlock(final PendingBlockId pendingBlockId, final Sha256Hash previousBlockHash) throws DatabaseException {
        final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();

        _databaseConnection.executeSql(
            new Query("UPDATE pending_blocks SET previous_block_hash = ?, timestamp = ? WHERE id = ?")
                .setParameter(previousBlockHash)
                .setParameter(currentTimestamp)
                .setParameter(pendingBlockId)
        );
    }

    protected void _insertPendingBlockData(final PendingBlockId pendingBlockId, final ByteArray blockData) throws DatabaseException {
        _databaseConnection.executeSql(
            new Query("INSERT IGNORE INTO pending_block_data (pending_block_id, data) VALUES (?, ?)")
                .setParameter(pendingBlockId)
                .setParameter(blockData.getBytes())
        );
    }

    protected void _deletePendingBlock(final PendingBlockId pendingBlockId) throws DatabaseException {
        _databaseConnection.executeSql(
            new Query("DELETE FROM pending_blocks WHERE id = ?")
                .setParameter(pendingBlockId)
        );
    }

    protected void _deletePendingBlocks(final List<PendingBlockId> pendingBlockIds) throws DatabaseException {
        if (pendingBlockIds.isEmpty()) { return; }

        _databaseConnection.executeSql(
            new Query("DELETE FROM pending_blocks WHERE id IN (" + DatabaseUtil.createInClause(pendingBlockIds) + ")")
        );
    }

    protected Boolean _hasBlockData(final PendingBlockId pendingBlockId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM pending_block_data WHERE pending_block_id = ?")
                .setParameter(pendingBlockId)
        );
        return (rows.size() > 0);
    }

    protected ByteArray _getBlockData(final PendingBlockId pendingBlockId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, data FROM pending_block_data WHERE pending_block_id = ?")
                .setParameter(pendingBlockId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return MutableByteArray.wrap(row.getBytes("data"));
    }

    protected PendingBlock _getPendingBlock(final PendingBlockId pendingBlockId, final Boolean includeDataIfAvailable) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, hash, previous_block_hash FROM pending_blocks WHERE id = ?")
                .setParameter(pendingBlockId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        final Sha256Hash blockHash = Sha256Hash.fromHexString(row.getString("hash"));
        final Sha256Hash previousBlockHash = Sha256Hash.fromHexString(row.getString("previous_block_hash"));
        final ByteArray blockData;
        {
            if (includeDataIfAvailable) {
                blockData = _getBlockData(pendingBlockId);
            }
            else {
                blockData = null;
            }
        }

        return new PendingBlock(blockHash, previousBlockHash, blockData);
    }

    public PendingBlockDatabaseManager(final DatabaseConnection databaseConnection) {
        _databaseConnection = databaseConnection;
    }

    public PendingBlockId getPendingBlockId(final Sha256Hash blockHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _getPendingBlockId(blockHash);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Boolean hasBlockData(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _hasBlockData(pendingBlockId);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Boolean pendingBlockExists(final Sha256Hash blockHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final PendingBlockId pendingBlockId = _getPendingBlockId(blockHash);
            return (pendingBlockId != null);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public List<PendingBlockId> getPendingBlockIdsWithPreviousBlockHash(final Sha256Hash previousBlockHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _getPendingBlockIdsWithPreviousBlockHash(previousBlockHash);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public PendingBlockId insertBlockHash(final Sha256Hash blockHash) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            return _storePendingBlock(blockHash, null);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Inserts the blockHash into PendingBlocks if it does not exist.
     *  If previousBlockHash is provided, then the PendingBlock is updated to include the previousBlockHash.
     */
    public PendingBlockId storeBlockHash(final Sha256Hash blockHash) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final PendingBlockId existingPendingBlockId = _getPendingBlockId(blockHash);
            if (existingPendingBlockId != null) { return existingPendingBlockId; }

            return _storePendingBlock(blockHash, null);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Inserts the blockHash into PendingBlocks if it does not exist.
     *  If previousBlockHash is provided, then the PendingBlock is updated to include the previousBlockHash.
     */
    public PendingBlockId storeBlockHash(final Sha256Hash blockHash, final Sha256Hash previousBlockHash) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final PendingBlockId existingPendingBlockId = _getPendingBlockId(blockHash);
            if (existingPendingBlockId != null) { return existingPendingBlockId; }

            DatabaseException deadlockException = null;
            for (int i = 0; i < 3; ++i) {
                try {
                    return _storePendingBlock(blockHash, previousBlockHash);
                }
                catch (final DatabaseException exception) {
                    deadlockException = exception;
                }
            }
            throw deadlockException;

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public PendingBlockId storeBlock(final Block block) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Sha256Hash blockHash = block.getHash();
            final Sha256Hash previousBlockHash = block.getPreviousBlockHash();

            final PendingBlockId pendingBlockId;
            {
                final PendingBlockId existingPendingBlockId = _getPendingBlockId(blockHash);
                if (existingPendingBlockId != null) {
                    _updatePendingBlock(existingPendingBlockId, previousBlockHash);
                    pendingBlockId = existingPendingBlockId;
                }
                else {
                    pendingBlockId = _storePendingBlock(blockHash, previousBlockHash);
                }
            }

            final BlockDeflater blockDeflater = new BlockDeflater();
            _insertPendingBlockData(pendingBlockId, blockDeflater.toBytes(block));
            return pendingBlockId;

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public List<Tuple<Sha256Hash, Sha256Hash>> selectPriorityPendingBlocksWithUnknownNodeInventory(final List<NodeId> connectedNodeIds) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT blocks.block_height, pending_blocks.hash FROM pending_blocks LEFT OUTER JOIN pending_block_data ON pending_blocks.id = pending_block_data.pending_block_id LEFT OUTER JOIN node_blocks_inventory ON node_blocks_inventory.pending_block_id = pending_blocks.id AND node_blocks_inventory.node_id IN (" + DatabaseUtil.createInClause(connectedNodeIds) + ") LEFT OUTER JOIN blocks ON blocks.hash = pending_blocks.hash WHERE (pending_block_data.id IS NULL) AND (node_blocks_inventory.id IS NULL) ORDER BY pending_blocks.priority ASC, pending_blocks.id ASC LIMIT 500")
            );

            final MutableList<Tuple<Sha256Hash, Sha256Hash>> downloadPlan = new MutableList<>(rows.size());

            Tuple<Sha256Hash, Sha256Hash> blockHashStartEnd = null;
            Long tupleStartingBlockHeight = null; // The blockHeight of blockHashStartEnd.first...
            for (final Row row : rows) {
                final Long blockHeight = row.getLong("block_height");
                final Sha256Hash blockHash = Sha256Hash.fromHexString(row.getString("hash"));

                boolean addTupleToDownloadPlan = false;
                boolean createNewTuple = false;
                if (blockHashStartEnd == null) {
                    createNewTuple = true;

                    if (blockHeight == null) {
                        addTupleToDownloadPlan = true;
                    }
                }
                else {
                    if (blockHeight == null) {
                        addTupleToDownloadPlan = true;
                        createNewTuple = true;
                    }
                    else {
                        if (tupleStartingBlockHeight == null) {
                            createNewTuple = true;
                            addTupleToDownloadPlan = true;
                        }
                        else {
                            final Long blockHeightDifference = (blockHeight - tupleStartingBlockHeight);
                            if ((blockHeightDifference < 0) || (blockHeightDifference >= 500)) {
                                addTupleToDownloadPlan = true;
                            }
                            else {
                                blockHashStartEnd.second = blockHash;
                            }
                        }
                    }
                }

                if (addTupleToDownloadPlan) {
                    if (blockHashStartEnd != null) {
                        downloadPlan.add(blockHashStartEnd);
                        blockHashStartEnd = null;
                        tupleStartingBlockHeight = null;
                    }
                }

                if (createNewTuple) {
                    blockHashStartEnd = new Tuple<>();
                    blockHashStartEnd.first = blockHash;
                    tupleStartingBlockHeight = blockHeight;
                }
            }
            if (blockHashStartEnd != null) {
                downloadPlan.add(blockHashStartEnd);
            }

            return downloadPlan;

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Boolean nodesHaveBlockInventory(final List<NodeId> connectedNodeIds, final Sha256Hash blockHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_blocks.id FROM pending_blocks INNER JOIN node_blocks_inventory ON node_blocks_inventory.pending_block_id = pending_blocks.id WHERE (pending_blocks.hash = ?) AND (node_blocks_inventory.node_id IN (" + DatabaseUtil.createInClause(connectedNodeIds) + ")) LIMIT 1")
                    .setParameter(blockHash)
            );

            return (! rows.isEmpty());

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Map<PendingBlockId, NodeId> selectIncompletePendingBlocks(final List<NodeId> connectedNodeIds, final Integer maxBlockCount) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final Long minSecondsBetweenDownloadAttempts = 5L;
            final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT node_blocks_inventory.node_id, pending_blocks.id AS pending_block_id FROM pending_blocks LEFT OUTER JOIN pending_block_data ON pending_blocks.id = pending_block_data.pending_block_id INNER JOIN node_blocks_inventory ON node_blocks_inventory.pending_block_id = pending_blocks.id WHERE (pending_block_data.id IS NULL) AND ( (? - COALESCE(last_download_attempt_timestamp, 0)) > ? ) AND (node_blocks_inventory.node_id IN (" + DatabaseUtil.createInClause(connectedNodeIds) + ")) ORDER BY pending_blocks.priority ASC, pending_blocks.id ASC LIMIT " + Util.coalesce(maxBlockCount, Integer.MAX_VALUE))
                    .setParameter(currentTimestamp)
                    .setParameter(minSecondsBetweenDownloadAttempts)
            );

            final HashMap<PendingBlockId, NodeId> downloadPlan = new HashMap<>(rows.size());
            for (final Row row : rows) {
                final NodeId nodeId = NodeId.wrap(row.getLong("node_id"));
                final PendingBlockId pendingBlockId = PendingBlockId.wrap(row.getLong("pending_block_id"));
                downloadPlan.put(pendingBlockId, nodeId);
            }
            return downloadPlan;

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public PendingBlockId selectCandidatePendingBlockId() throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_blocks.id FROM pending_blocks INNER JOIN pending_block_data ON pending_blocks.id = pending_block_data.pending_block_id INNER JOIN blocks ON blocks.hash = pending_blocks.previous_block_hash INNER JOIN block_transactions ON block_transactions.block_id = blocks.id GROUP BY block_transactions.block_id ORDER BY pending_blocks.priority ASC LIMIT 1")
            );
            if (rows.isEmpty()) { return null; }

            final Row row = rows.get(0);
            return PendingBlockId.wrap(row.getLong("id"));

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Sha256Hash getPendingBlockHash(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT id, hash FROM pending_blocks WHERE id = ?")
                    .setParameter(pendingBlockId)
            );
            if (rows.isEmpty()) { return null; }

            final Row row = rows.get(0);
            return Sha256Hash.fromHexString(row.getString("hash"));

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public void incrementFailedDownloadCount(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("UPDATE pending_blocks SET failed_download_count = failed_download_count + 1, priority = priority + 60 WHERE id = ?")
                    .setParameter(pendingBlockId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void updateLastDownloadAttemptTime(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
            _databaseConnection.executeSql(
                new Query("UPDATE pending_blocks SET last_download_attempt_timestamp = ? WHERE id = ?")
                    .setParameter(currentTimestamp)
                    .setParameter(pendingBlockId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void setPriority(final PendingBlockId pendingBlockId, final Long priority) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("UPDATE pending_blocks SET priority = ? WHERE id = ?")
                    .setParameter(priority)
                    .setParameter(pendingBlockId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void purgeFailedPendingBlocks(final Integer maxFailedDownloadCount) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_blocks.id FROM pending_blocks LEFT OUTER JOIN pending_block_data ON (pending_blocks.id = pending_block_data.pending_block_id) WHERE pending_blocks.failed_download_count > ? AND pending_block_data.id IS NULL")
                    .setParameter(maxFailedDownloadCount)
            );

            final MutableList<PendingBlockId> pendingBlockIds = new MutableList<>(rows.size());
            for (final Row row : rows) {
                final PendingBlockId pendingBlockId = PendingBlockId.wrap(row.getLong("id"));
                Logger.log("Deleting Failed Pending Block: " + pendingBlockId);
                pendingBlockIds.add(pendingBlockId);
            }

            _deletePendingBlocks(pendingBlockIds);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Deletes any pending blocks that haven't been downloaded and do not have a peer to download them from.
     *  This can happen when a peer broadcasting block inventory disconnects and there are no other peers aware of their chain.
     */
    public void purgeUnlocatablePendingBlocks(final List<NodeId> connectedNodeIds) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                // "Delete any pending_blocks that have not already been downloaded and do not have a connected node to download from..."
                // new Query("SELECT pending_blocks.id FROM pending_blocks WHERE NOT EXISTS (SELECT * FROM pending_block_data WHERE pending_block_data.pending_block_id = pending_blocks.id) AND NOT EXISTS (SELECT * FROM node_blocks_inventory WHERE node_blocks_inventory.node_id IN () AND node_blocks_inventory.pending_block_id = pending_blocks.id)") // This query is easier to understand, but is likely to perform worse...
                new Query("SELECT pending_blocks.id FROM pending_blocks LEFT OUTER JOIN node_blocks_inventory ON (node_blocks_inventory.pending_block_id = pending_blocks.id AND node_blocks_inventory.node_id IN (" + DatabaseUtil.createInClause(connectedNodeIds) + ")) LEFT OUTER JOIN pending_block_data ON (pending_blocks.id = pending_block_data.pending_block_id) WHERE node_blocks_inventory.id IS NULL AND pending_block_data.id IS NULL")
            );

            final MutableList<PendingBlockId> pendingBlockIds = new MutableList<>(rows.size());
            for (final Row row : rows) {
                final PendingBlockId pendingBlockId = PendingBlockId.wrap(row.getLong("id"));
                Logger.log("Deleting Unlocatable Pending Block: " + pendingBlockId);
                pendingBlockIds.add(pendingBlockId);
            }

            _deletePendingBlocks(pendingBlockIds);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public PendingBlock getPendingBlock(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _getPendingBlock(pendingBlockId, true);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public void deletePendingBlock(final PendingBlockId pendingBlockId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _deletePendingBlock(pendingBlockId);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Deletes any pending blocks that have been completely processed.
     *  This can happen (rarely) due to a race condition between the InventoryHandler and the BlockchainBuilder...
     *  Since duplicates are fairly innocuous, it is better to run a cleanup than to introduce contention between the two components.
     */
    public void cleanupPendingBlocks() throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("DELETE FROM pending_blocks WHERE EXISTS (SELECT * FROM blocks INNER JOIN block_transactions ON blocks.id = block_transactions.block_id WHERE pending_blocks.hash = blocks.hash)")
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }
}

class DisabledReadLock extends ReentrantReadWriteLock.ReadLock {
    public DisabledReadLock(final ReentrantReadWriteLock lock) {
        super(lock);
    }

    @Override
    public void lock() { }

    @Override
    public void unlock() { }
}

class DisabledWriteLock extends ReentrantReadWriteLock.WriteLock {
    public DisabledWriteLock(final ReentrantReadWriteLock lock) {
        super(lock);
    }

    @Override
    public void lock() { }

    @Override
    public void unlock() { }
}