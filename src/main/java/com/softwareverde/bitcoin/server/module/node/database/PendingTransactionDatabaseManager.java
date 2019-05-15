package com.softwareverde.bitcoin.server.module.node.database;

import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.module.node.sync.transaction.pending.PendingTransaction;
import com.softwareverde.bitcoin.server.module.node.sync.transaction.pending.PendingTransactionId;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionDeflater;
import com.softwareverde.bitcoin.transaction.TransactionInflater;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.Query;
import com.softwareverde.database.Row;
import com.softwareverde.database.mysql.BatchedInsertQuery;
import com.softwareverde.database.util.DatabaseUtil;
import com.softwareverde.io.Logger;
import com.softwareverde.network.p2p.node.NodeId;
import com.softwareverde.util.type.time.SystemTime;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class PendingTransactionDatabaseManager {
    public static final Long MAX_ORPHANED_TRANSACTION_AGE_IN_SECONDS = (60 * 60L); // 1 Hour...

    public static final ReentrantReadWriteLock.ReadLock READ_LOCK;
    public static final ReentrantReadWriteLock.WriteLock WRITE_LOCK;
    static {
        final ReentrantReadWriteLock readWriteLock = new ReentrantReadWriteLock();
        READ_LOCK = readWriteLock.readLock();
        WRITE_LOCK = readWriteLock.writeLock();
    }

    protected final SystemTime _systemTime = new SystemTime();
    protected final DatabaseConnection _databaseConnection;

    protected PendingTransactionId _getPendingTransactionId(final Sha256Hash transactionHash) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM pending_transactions WHERE hash = ?")
                .setParameter(transactionHash)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return PendingTransactionId.wrap(row.getLong("id"));
    }

    protected PendingTransactionId _storePendingTransaction(final Sha256Hash transactionHash) throws DatabaseException {
        final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
        final Long priority = currentTimestamp;
        final Long pendingTransactionId = _databaseConnection.executeSql(
            new Query("INSERT IGNORE INTO pending_transactions (hash, timestamp, priority) VALUES (?, ?, ?)")
                .setParameter(transactionHash)
                .setParameter(currentTimestamp)
                .setParameter(priority)
        );

        if (pendingTransactionId == 0) {
            // The insert was ignored, so return the existing row.  This logic is necessary to prevent a race condition due to PendingTransactionDatabaseManager not locking...
            return _getPendingTransactionId(transactionHash);
        }

        return PendingTransactionId.wrap(pendingTransactionId);
    }

    protected void _insertPendingTransactionData(final PendingTransactionId pendingTransactionId, final ByteArray transactionData) throws DatabaseException {
        _databaseConnection.executeSql(
                new Query("INSERT IGNORE INTO pending_transaction_data (pending_transaction_id, data) VALUES (?, ?)")
                        .setParameter(pendingTransactionId)
                        .setParameter(transactionData.getBytes())
        );
    }

    protected void _deletePendingTransaction(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        _databaseConnection.executeSql(
            new Query("DELETE FROM pending_transactions WHERE id = ?")
                .setParameter(pendingTransactionId)
        );
    }

    protected void _deletePendingTransactions(final List<PendingTransactionId> pendingTransactionIds) throws DatabaseException {
        if (pendingTransactionIds.isEmpty()) { return; }

        _databaseConnection.executeSql(
            new Query("DELETE FROM pending_transactions WHERE id IN(" + DatabaseUtil.createInClause(pendingTransactionIds) + ")")
        );
    }

    protected Boolean _hasTransactionData(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id FROM pending_transaction_data WHERE pending_transaction_id = ?")
                .setParameter(pendingTransactionId)
        );
        return (rows.size() > 0);
    }

    protected ByteArray _getTransactionData(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, data FROM pending_transaction_data WHERE pending_transaction_id = ?")
                .setParameter(pendingTransactionId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        return MutableByteArray.wrap(row.getBytes("data"));
    }

    protected PendingTransaction _getPendingTransaction(final PendingTransactionId pendingTransactionId, final Boolean includeDataIfAvailable) throws DatabaseException {
        final java.util.List<Row> rows = _databaseConnection.query(
            new Query("SELECT id, hash FROM pending_transactions WHERE id = ?")
                .setParameter(pendingTransactionId)
        );
        if (rows.isEmpty()) { return null; }

        final Row row = rows.get(0);
        final Sha256Hash transactionHash = Sha256Hash.fromHexString(row.getString("hash"));
        final ByteArray transactionData;
        {
            if (includeDataIfAvailable) {
                transactionData = _getTransactionData(pendingTransactionId);
            }
            else {
                transactionData = null;
            }
        }

        return new PendingTransaction(transactionHash, transactionData);
    }

    protected void _updateTransactionDependencies(final Transaction transaction) throws DatabaseException {
        final PendingTransactionId pendingTransactionId = _getPendingTransactionId(transaction.getHash());
        if (pendingTransactionId == null) { return; }

        final List<TransactionInput> transactionInputs = transaction.getTransactionInputs();
        final HashSet<Sha256Hash> requiredTransactionHashes = new HashSet<>(transactionInputs.getSize());
        for (final TransactionInput transactionInput : transactionInputs) {
            final Sha256Hash transactionHash = transactionInput.getPreviousOutputTransactionHash();
            requiredTransactionHashes.add(transactionHash);
        }
        if (requiredTransactionHashes.isEmpty()) { return; }

        final BatchedInsertQuery batchedInsertQuery = new BatchedInsertQuery("INSERT IGNORE INTO pending_transactions_dependent_transactions (pending_transaction_id, hash) VALUES (?, ?)");
        for (final Sha256Hash transactionHash : requiredTransactionHashes) {
            batchedInsertQuery.setParameter(pendingTransactionId);
            batchedInsertQuery.setParameter(transactionHash);
        }
        _databaseConnection.executeSql(batchedInsertQuery);
    }

    public PendingTransactionDatabaseManager(final DatabaseConnection databaseConnection) {
        _databaseConnection = databaseConnection;
    }

    public PendingTransactionId getPendingTransactionId(final Sha256Hash transactionHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _getPendingTransactionId(transactionHash);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Boolean hasTransactionData(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            return _hasTransactionData(pendingTransactionId);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Boolean pendingTransactionExists(final Sha256Hash transactionHash) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final PendingTransactionId pendingTransactionId = _getPendingTransactionId(transactionHash);
            return (pendingTransactionId != null);

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public PendingTransactionId insertTransactionHash(final Sha256Hash transactionHash) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            return _storePendingTransaction(transactionHash);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public PendingTransactionId storeTransactionHash(final Sha256Hash transactionHash) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final PendingTransactionId existingPendingTransactionId = _getPendingTransactionId(transactionHash);
            if (existingPendingTransactionId != null) { return existingPendingTransactionId; }

            return _storePendingTransaction(transactionHash);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    /**
     * Inserts Transaction Hashes into the pending_transactions table that do not already exist.
     *  NOTE: Since existing Transaction Hashes are ignored, the returned Ids are not a one-to-one mapping of the provided hashes, unlike most other DatabaseManagers.
     */
    public List<PendingTransactionId> storeTransactionHashes(final List<Sha256Hash> transactionHashes) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
            final Long priority = currentTimestamp;
            final BatchedInsertQuery batchedInsertQuery = new BatchedInsertQuery("INSERT IGNORE INTO pending_transactions (hash, timestamp, priority) VALUES (?, ?, ?)");
            for (final Sha256Hash transactionHash : transactionHashes) {
                batchedInsertQuery.setParameter(transactionHash);
                batchedInsertQuery.setParameter(currentTimestamp);
                batchedInsertQuery.setParameter(priority);
            }

            final Long firstPendingTransactionId = _databaseConnection.executeSql(batchedInsertQuery);
            final Integer newRowCount = _databaseConnection.getRowsAffectedCount();

            final ImmutableListBuilder<PendingTransactionId> pendingTransactionIds = new ImmutableListBuilder<>(newRowCount);
            for (int i = 0; i < newRowCount; ++i) {
                pendingTransactionIds.add(PendingTransactionId.wrap(firstPendingTransactionId + i));
            }

            return pendingTransactionIds.build();

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public PendingTransactionId storeTransaction(final Transaction transaction) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Sha256Hash transactionHash = transaction.getHash();

            final PendingTransactionId pendingTransactionId;
            {
                final PendingTransactionId existingPendingTransactionId = _getPendingTransactionId(transactionHash);
                if (existingPendingTransactionId != null) {
                    pendingTransactionId = existingPendingTransactionId;
                }
                else {
                    pendingTransactionId = _storePendingTransaction(transactionHash);
                }
            }

            final TransactionDeflater transactionDeflater = new TransactionDeflater();
            _insertPendingTransactionData(pendingTransactionId, transactionDeflater.toBytes(transaction));

            _updateTransactionDependencies(transaction);

            return pendingTransactionId;

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public Map<NodeId, ? extends List<PendingTransactionId>> selectIncompletePendingTransactions(final List<NodeId> connectedNodeIds) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final Long minSecondsBetweenDownloadAttempts = 5L;
            final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT node_transactions_inventory.node_id, pending_transactions.id AS pending_transaction_id FROM pending_transactions LEFT OUTER JOIN pending_transaction_data ON pending_transactions.id = pending_transaction_data.pending_transaction_id INNER JOIN node_transactions_inventory ON node_transactions_inventory.pending_transaction_id = pending_transactions.id WHERE (pending_transaction_data.id IS NULL) AND ( (? - COALESCE(last_download_attempt_timestamp, 0)) > ? ) AND node_transactions_inventory.node_id IN (" + DatabaseUtil.createInClause(connectedNodeIds) + ") ORDER BY pending_transactions.priority ASC, pending_transactions.id ASC LIMIT 1024")
                    .setParameter(currentTimestamp)
                    .setParameter(minSecondsBetweenDownloadAttempts)
            );

            final HashMap<NodeId, MutableList<PendingTransactionId>> downloadPlan = new HashMap<>();
            for (final Row row : rows) {
                final NodeId nodeId = NodeId.wrap(row.getLong("node_id"));
                final PendingTransactionId pendingTransactionId = PendingTransactionId.wrap(row.getLong("pending_transaction_id"));
                if (! downloadPlan.containsKey(nodeId)) {
                    downloadPlan.put(nodeId, new MutableList<>());
                }

                final MutableList<PendingTransactionId> list = downloadPlan.get(nodeId);
                list.add(pendingTransactionId);
            }
            return downloadPlan;

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public List<PendingTransactionId> selectCandidatePendingTransactionIds() throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_transactions.id, pending_transactions.hash FROM pending_transactions INNER JOIN pending_transaction_data ON pending_transaction_data.pending_transaction_id = pending_transactions.id WHERE NOT EXISTS (SELECT * FROM pending_transactions_dependent_transactions LEFT OUTER JOIN transactions ON transactions.hash = pending_transactions_dependent_transactions.hash WHERE (transactions.id IS NULL) AND (pending_transactions_dependent_transactions.pending_transaction_id = pending_transactions.id))")
            );

            final ImmutableListBuilder<PendingTransactionId> pendingTransactionIds = new ImmutableListBuilder<>(rows.size());
            for (final Row row : rows) {
                final PendingTransactionId pendingTransactionId = PendingTransactionId.wrap(row.getLong("id"));
                pendingTransactionIds.add(pendingTransactionId);
            }
            return pendingTransactionIds.build();

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Transaction getPendingTransaction(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_transactions.id, pending_transactions.hash, pending_transaction_data.data FROM pending_transactions INNER JOIN pending_transaction_data ON pending_transactions.id = pending_transaction_data.pending_transaction_id WHERE pending_transactions.id = ?")
                    .setParameter(pendingTransactionId)
            );
            if (rows.isEmpty()) { return null; }

            final Row row = rows.get(0);
            final Sha256Hash transactionHash = Sha256Hash.fromHexString(row.getString("hash"));
            final ByteArray transactionData = MutableByteArray.wrap(row.getBytes("data"));

            final TransactionInflater transactionInflater = new TransactionInflater();
            final Transaction transaction = transactionInflater.fromBytes(transactionData);
            if (transaction == null) {
                _deletePendingTransaction(pendingTransactionId);
                Logger.log("NOTICE: Error inflating pending transaction: " + transactionHash + " " + transactionData);
            }

            return transaction;

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public Sha256Hash getPendingTransactionHash(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            READ_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT id, hash FROM pending_transactions WHERE id = ?")
                    .setParameter(pendingTransactionId)
            );
            if (rows.isEmpty()) { return null; }

            final Row row = rows.get(0);
            return Sha256Hash.fromHexString(row.getString("hash"));

        }
        finally {
            READ_LOCK.unlock();
        }
    }

    public void incrementFailedDownloadCount(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("UPDATE pending_transactions SET failed_download_count = failed_download_count + 1, priority = priority + 60 WHERE id = ?")
                    .setParameter(pendingTransactionId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void updateLastDownloadAttemptTime(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Long currentTimestamp = _systemTime.getCurrentTimeInSeconds();
            _databaseConnection.executeSql(
                new Query("UPDATE pending_transactions SET last_download_attempt_timestamp = ? WHERE id = ?")
                    .setParameter(currentTimestamp)
                    .setParameter(pendingTransactionId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void setPriority(final PendingTransactionId pendingTransactionId, final Long priority) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("UPDATE pending_transactions SET priority = ? WHERE id = ?")
                    .setParameter(priority)
                    .setParameter(pendingTransactionId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void purgeFailedPendingTransactions(final Integer maxFailedDownloadCount) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final java.util.List<Row> rows = _databaseConnection.query(
                new Query("SELECT pending_transactions.id FROM pending_transactions LEFT OUTER JOIN pending_transaction_data ON (pending_transactions.id = pending_transaction_data.pending_transaction_id) WHERE pending_transactions.failed_download_count > ? AND pending_transaction_data.id IS NULL")
                    .setParameter(maxFailedDownloadCount)
            );

            final MutableList<PendingTransactionId> pendingTransactionIds = new MutableList<>(rows.size());
            for (final Row row : rows) {
                final PendingTransactionId pendingTransactionId = PendingTransactionId.wrap(row.getLong("id"));
                Logger.log("Deleting Failed Pending Transaction: " + pendingTransactionId);
                pendingTransactionIds.add(pendingTransactionId);
            }

            _deletePendingTransactions(pendingTransactionIds);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void purgeExpiredOrphanedTransactions() throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            final Long minimumTimestamp = (_systemTime.getCurrentTimeInSeconds() - MAX_ORPHANED_TRANSACTION_AGE_IN_SECONDS);
            _databaseConnection.executeSql(
                new Query("DELETE pending_transactions FROM pending_transactions LEFT OUTER JOIN transactions ON transactions.hash = pending_transactions.hash WHERE (transactions.id IS NOT NULL) OR (pending_transactions.timestamp < ?)")
                    .setParameter(minimumTimestamp)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void deletePendingTransaction(final PendingTransactionId pendingTransactionId) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _databaseConnection.executeSql(
                new Query("DELETE FROM pending_transactions WHERE id = ?")
                    .setParameter(pendingTransactionId)
            );

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void deletePendingTransactions(final List<PendingTransactionId> pendingTransactionIds) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _deletePendingTransactions(pendingTransactionIds);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }

    public void updateTransactionDependencies(final Transaction transaction) throws DatabaseException {
        try {
            WRITE_LOCK.lock();

            _updateTransactionDependencies(transaction);

        }
        finally {
            WRITE_LOCK.unlock();
        }
    }
}
