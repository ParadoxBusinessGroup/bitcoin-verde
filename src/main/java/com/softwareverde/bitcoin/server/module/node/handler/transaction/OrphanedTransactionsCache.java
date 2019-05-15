package com.softwareverde.bitcoin.server.module.node.handler.transaction;

import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.module.node.database.TransactionOutputDatabaseManager;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.input.TransactionInput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutput;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputId;
import com.softwareverde.bitcoin.transaction.output.identifier.TransactionOutputIdentifier;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.io.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

public class OrphanedTransactionsCache {
    public static final Integer MAX_ORPHANED_TRANSACTION_COUNT = (128 * 1024);

    protected final HashMap<TransactionOutputIdentifier, HashSet<Transaction>> _orphanedTransactions = new HashMap<>(MAX_ORPHANED_TRANSACTION_COUNT);
    protected final HashSet<Transaction> _orphanedTransactionSet = new HashSet<>();
    protected final LinkedList<Transaction> _orphanedTransactionsByAge = new LinkedList<>();
    protected final DatabaseManagerCache _databaseManagerCache;

    protected void _removeOrphanedTransaction(final Transaction transaction) {
        _orphanedTransactionSet.remove(transaction);

        for (final TransactionInput transactionInput : transaction.getTransactionInputs()) {
            final TransactionOutputIdentifier transactionOutputIdentifier = TransactionOutputIdentifier.fromTransactionInput(transactionInput);
            final HashSet<Transaction> queuedTransactions = _orphanedTransactions.get(transactionOutputIdentifier);
            if (queuedTransactions == null) { continue; }

            queuedTransactions.remove(transaction);

            if (queuedTransactions.isEmpty()) {
                _orphanedTransactions.remove(transactionOutputIdentifier);
            }
        }

        Logger.log("Purging old orphaned Transaction: " + transaction.getHash() + " (" + _orphanedTransactionSet.size() + " / " + MAX_ORPHANED_TRANSACTION_COUNT + ")");
    }

    protected void _purgeOldTransactions() {
        final Integer itemsToRemoveCount = (MAX_ORPHANED_TRANSACTION_COUNT / 2);
        for (int i = 0; i < itemsToRemoveCount; ++i) {
            if (_orphanedTransactionsByAge.isEmpty()) { break; }

            final Transaction transaction = _orphanedTransactionsByAge.removeFirst();
            if (_orphanedTransactionSet.contains(transaction)) {
                _removeOrphanedTransaction(transaction);
            }
        }
    }

    public OrphanedTransactionsCache(final DatabaseManagerCache databaseManagerCache) {
        _databaseManagerCache = databaseManagerCache;
    }

    public synchronized void add(final Transaction transaction, final DatabaseConnection databaseConnection) throws DatabaseException {
        final Boolean transactionIsUnique = _orphanedTransactionSet.add(transaction);
        if (! transactionIsUnique) { return; }

        Logger.log("Queuing orphaned Transaction: " + transaction.getHash() + " (" + _orphanedTransactionSet.size() + " / " + MAX_ORPHANED_TRANSACTION_COUNT + ")");

        _orphanedTransactionsByAge.addLast(transaction);
        if (_orphanedTransactionSet.size() > MAX_ORPHANED_TRANSACTION_COUNT) {
            _purgeOldTransactions();
        }

        final TransactionOutputDatabaseManager transactionOutputDatabaseManager = new TransactionOutputDatabaseManager(databaseConnection, _databaseManagerCache);

        for (final TransactionInput transactionInput : transaction.getTransactionInputs()) {
            final TransactionOutputIdentifier transactionOutputIdentifier = TransactionOutputIdentifier.fromTransactionInput(transactionInput);
            final TransactionOutputId transactionOutputId = transactionOutputDatabaseManager.findTransactionOutput(transactionOutputIdentifier);
            if (transactionOutputId == null) {
                if (! _orphanedTransactions.containsKey(transactionOutputIdentifier)) {
                    _orphanedTransactions.put(transactionOutputIdentifier, new HashSet<>());
                }

                final HashSet<Transaction> queuedTransactions = _orphanedTransactions.get(transactionOutputIdentifier);
                queuedTransactions.add(transaction);
            }
        }
    }

    public synchronized Set<Transaction> onTransactionAdded(final Transaction transaction) {
        final HashSet<Transaction> possiblyEligibleTransactions = new HashSet<>();

        final Sha256Hash transactionHash = transaction.getHash();

        for (final TransactionOutput transactionOutput : transaction.getTransactionOutputs()) {
            final Integer transactionOutputIndex = transactionOutput.getIndex();
            final TransactionOutputIdentifier transactionOutputIdentifier = new TransactionOutputIdentifier(transactionHash, transactionOutputIndex);
            final HashSet<Transaction> queuedTransactions = _orphanedTransactions.remove(transactionOutputIdentifier);
            if (queuedTransactions == null) { continue; }

            possiblyEligibleTransactions.addAll(queuedTransactions);

            Logger.log("Promoting orphaned Transaction: " + transaction.getHash());
        }

        if (_orphanedTransactionSet.contains(transaction)) {
            _removeOrphanedTransaction(transaction);
        }

        return possiblyEligibleTransactions;
    }
}
