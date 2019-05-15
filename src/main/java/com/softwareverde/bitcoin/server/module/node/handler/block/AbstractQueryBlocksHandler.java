package com.softwareverde.bitcoin.server.module.node.handler.block;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.chain.segment.BlockchainSegmentId;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.DatabaseConnection;
import com.softwareverde.bitcoin.server.database.DatabaseConnectionFactory;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.server.module.node.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.BlockHeaderDatabaseManager;
import com.softwareverde.bitcoin.server.module.node.database.BlockchainDatabaseManager;
import com.softwareverde.bitcoin.server.node.BitcoinNode;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.io.Logger;
import com.softwareverde.util.Util;

public abstract class AbstractQueryBlocksHandler implements BitcoinNode.QueryBlockHeadersCallback {
    protected static class StartingBlock {
        public final BlockchainSegmentId selectedBlockchainSegmentId;
        public final BlockId startingBlockId;
        public final Boolean matchWasFound;

        public StartingBlock(final BlockchainSegmentId blockchainSegmentId, final BlockId startingBlockId, final Boolean matchWasFound) {
            this.selectedBlockchainSegmentId = blockchainSegmentId;
            this.startingBlockId = startingBlockId;
            this.matchWasFound = matchWasFound;
        }
    }

    protected final DatabaseConnectionFactory _databaseConnectionFactory;
    protected final DatabaseManagerCache _databaseManagerCache;

    protected AbstractQueryBlocksHandler(final DatabaseConnectionFactory databaseConnectionFactory, final DatabaseManagerCache databaseManagerCache) {
        _databaseConnectionFactory = databaseConnectionFactory;
        _databaseManagerCache = databaseManagerCache;
    }

    /**
     * Returns the children BlockIds of the provided blockId, until either maxCount is reached or desiredBlockHash is reached.
     *  The returned list of BlockIds does not include blockId.
     */
    protected List<BlockId> _findBlockChildrenIds(final BlockId blockId, final Sha256Hash desiredBlockHash, final BlockchainSegmentId blockchainSegmentId, final Integer maxCount, final BlockHeaderDatabaseManager blockDatabaseManager) throws DatabaseException {
        final MutableList<BlockId> returnedBlockIds = new MutableList<>();

        BlockId nextBlockId = blockId;
        while (true) {
            final Sha256Hash addedBlockHash = blockDatabaseManager.getBlockHash(nextBlockId);
            if (addedBlockHash == null) { break; }

            if (! Util.areEqual(blockId, nextBlockId)) {
                returnedBlockIds.add(nextBlockId);
            }

            if (addedBlockHash.equals(desiredBlockHash)) { break; }
            if (returnedBlockIds.getSize() >= maxCount) { break; }

            nextBlockId = blockDatabaseManager.getChildBlockId(blockchainSegmentId, nextBlockId);
            if (nextBlockId == null) { break; }
        }

        return returnedBlockIds;
    }

    protected StartingBlock _getStartingBlock(final List<Sha256Hash> blockHashes, final Boolean matchedHeaderMustHaveTransactions, final Sha256Hash desiredBlockHash, final DatabaseConnection databaseConnection) throws DatabaseException {
        final BlockHeaderDatabaseManager blockHeaderDatabaseManager = new BlockHeaderDatabaseManager(databaseConnection, _databaseManagerCache);
        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(databaseConnection, _databaseManagerCache);
        final BlockchainDatabaseManager blockchainDatabaseManager = new BlockchainDatabaseManager(databaseConnection, _databaseManagerCache);

        final boolean matchWasFound;
        final BlockchainSegmentId blockchainSegmentId;
        final BlockId startingBlockId;
        {
            BlockId foundBlockId = null;
            for (final Sha256Hash blockHash : blockHashes) {
                if (matchedHeaderMustHaveTransactions) {
                    final Boolean blockExists = blockDatabaseManager.blockHeaderHasTransactions(blockHash);
                    if (blockExists) {
                        foundBlockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);
                        break;
                    }
                }
                else {
                    foundBlockId = blockHeaderDatabaseManager.getBlockHeaderId(blockHash);
                    if (foundBlockId != null) {
                        break;
                    }
                }
            }

            matchWasFound = (foundBlockId != null);

            if (foundBlockId != null) {
                final BlockId desiredBlockId = blockHeaderDatabaseManager.getBlockHeaderId(desiredBlockHash);
                if (desiredBlockId != null) {
                    blockchainSegmentId = blockHeaderDatabaseManager.getBlockchainSegmentId(desiredBlockId);
                }
                else {
                    final BlockchainSegmentId foundBlockBlockchainSegmentId = blockHeaderDatabaseManager.getBlockchainSegmentId(foundBlockId);
                    blockchainSegmentId = blockchainDatabaseManager.getHeadBlockchainSegmentIdOfBlockchainSegment(foundBlockBlockchainSegmentId);
                }
            }
            else {
                final Sha256Hash headBlockHash = blockDatabaseManager.getHeadBlockHash();
                if (headBlockHash != null) {
                    final BlockId genesisBlockId = blockHeaderDatabaseManager.getBlockHeaderId(Block.GENESIS_BLOCK_HASH);
                    foundBlockId = genesisBlockId;
                    blockchainSegmentId = blockchainDatabaseManager.getHeadBlockchainSegmentId();
                }
                else {
                    foundBlockId = null;
                    blockchainSegmentId = null;
                }
            }

            startingBlockId = foundBlockId;
        }

        if ( (blockchainSegmentId == null) || (startingBlockId == null) ) {
            Logger.log("QueryBlocksHandler._getStartingBlock: " + blockchainSegmentId + " " + startingBlockId);
            return null;
        }

        return new StartingBlock(blockchainSegmentId, startingBlockId, matchWasFound);
    }
}
