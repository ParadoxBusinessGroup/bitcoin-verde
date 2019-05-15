package com.softwareverde.bitcoin.server.module.node.handler;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.DatabaseConnectionFactory;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.message.type.query.response.error.NotFoundResponseMessage;
import com.softwareverde.bitcoin.server.message.type.query.response.hash.InventoryItem;
import com.softwareverde.bitcoin.server.message.type.query.response.hash.InventoryItemType;
import com.softwareverde.bitcoin.server.module.node.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.BlockHeaderDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.TransactionDatabaseManager;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.io.Logger;
import com.softwareverde.util.Util;
import com.softwareverde.util.timer.NanoTimer;

import java.util.HashSet;

public class RequestDataHandler implements BitcoinNode.RequestDataCallback {
    public static final BitcoinNode.RequestDataCallback IGNORE_REQUESTS_HANDLER = new BitcoinNode.RequestDataCallback() {
        @Override
        public void run(final List<InventoryItem> dataHashes, final BitcoinNode bitcoinNode) { }
    };

    protected final DatabaseConnectionFactory _databaseConnectionFactory;
    protected final DatabaseManagerCache _databaseManagerCache;

    public RequestDataHandler(final DatabaseConnectionFactory databaseConnectionFactory, final DatabaseManagerCache databaseManagerCache) {
        _databaseConnectionFactory = databaseConnectionFactory;
        _databaseManagerCache = databaseManagerCache;
    }

    @Override
    public void run(final List<InventoryItem> dataHashes, final BitcoinNode bitcoinNode) {
        try (final DatabaseConnection databaseConnection = _databaseConnectionFactory.newConnection()) {
            final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(databaseConnection, _databaseManagerCache);
            final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection, _databaseManagerCache);
            final TransactionDatabaseManager transactionDatabaseManager = new TransactionDatabaseManager(databaseConnection, _databaseManagerCache);

            final MutableList<InventoryItem> notFoundDataHashes = new MutableList<>();

            final HashSet<InventoryItem> processedDataHashes = new HashSet<>(dataHashes.getSize());

            for (final InventoryItem inventoryItem : dataHashes) {
                { // Avoid duplicate inventoryItems... This was encountered during the initial block download of an Android SPV wallet.
                    if (processedDataHashes.contains(inventoryItem)) { continue; }
                    processedDataHashes.add(inventoryItem);
                }

                if (! bitcoinNode.isConnected()) { break; }

                switch (inventoryItem.getItemType()) {

                    case MERKLE_BLOCK:
                    case BLOCK: {
                        final NanoTimer getBlockDataTimer = new NanoTimer();
                        getBlockDataTimer.start();
                        final Sha256Hash blockHash = inventoryItem.getItemHash();
                        final BlockId blockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);

                        if (blockId == null) {
                            notFoundDataHashes.add(inventoryItem);
                            continue;
                        }

                        final Block block = blockDatabaseManager.getBlock(blockId);
                        if (block == null) {
                            Logger.log("Error inflating Block: " + blockHash);
                            notFoundDataHashes.add(inventoryItem);
                            continue;
                        }

                        if (inventoryItem.getItemType() == InventoryItemType.MERKLE_BLOCK) {
                            bitcoinNode.transmitMerkleBlock(block);
                        }
                        else {
                            bitcoinNode.transmitBlock(block);
                        }

                        getBlockDataTimer.stop();
                        Logger.log("GetBlockData: " + blockHash + " "  + bitcoinNode.getRemoteNodeIpAddress() + " " + getBlockDataTimer.getMillisecondsElapsed() + "ms");

                        final Sha256Hash batchContinueHash = bitcoinNode.getBatchContinueHash();
                        if (Util.areEqual(batchContinueHash, blockHash)) {
                            final Sha256Hash headBlockHash = blockHeaderDatabaseManager.getHeadBlockHeaderHash();
                            bitcoinNode.transmitBatchContinueHash(headBlockHash);
                        }
                    } break;

                    case TRANSACTION: {
                        final NanoTimer getTransactionTimer = new NanoTimer();
                        getTransactionTimer.start();

                        final Sha256Hash transactionHash = inventoryItem.getItemHash();
                        final TransactionId transactionId = transactionDatabaseManager.getTransactionId(transactionHash);
                        if (transactionId == null) {
                            notFoundDataHashes.add(inventoryItem);
                            continue;
                        }

                        final Transaction transaction = transactionDatabaseManager.getTransaction(transactionId);
                        if (transaction == null) {
                            Logger.log("Error inflating Transaction: " + transactionHash);
                            notFoundDataHashes.add(inventoryItem);
                            continue;
                        }

                        bitcoinNode.transmitTransaction(transaction);

                        getTransactionTimer.stop();
                        Logger.log("GetTransactionData: " + transactionHash + " to " + bitcoinNode.getRemoteNodeIpAddress() + " " + getTransactionTimer.getMillisecondsElapsed() + "ms");
                    } break;

                    default: {
                        Logger.log("Unsupported RequestDataMessage Type: " + inventoryItem.getItemType());
                    } break;
                }
            }

            if (! notFoundDataHashes.isEmpty()) {
                final NotFoundResponseMessage notFoundResponseMessage = new NotFoundResponseMessage();
                for (final InventoryItem inventoryItem : notFoundDataHashes) {
                    notFoundResponseMessage.addItem(inventoryItem);
                }
                bitcoinNode.queueMessage(notFoundResponseMessage);
            }
        }
        catch (final DatabaseException exception) { Logger.log(exception); }
    }
}
