package com.softwareverde.bitcoin.server.module.node.sync;

import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.chain.segment.BlockChainSegmentId;
import com.softwareverde.bitcoin.server.database.BlockDatabaseManager;
import com.softwareverde.bitcoin.server.database.cache.DatabaseManagerCache;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.database.DatabaseException;
import com.softwareverde.database.mysql.MysqlDatabaseConnection;

public class BlockFinderHashesBuilder {
    protected final MysqlDatabaseConnection _databaseConnection;
    protected final DatabaseManagerCache _databaseManagerCache;

    public BlockFinderHashesBuilder(final MysqlDatabaseConnection databaseConnection, final DatabaseManagerCache databaseManagerCache) {
        _databaseConnection = databaseConnection;
        _databaseManagerCache = databaseManagerCache;
    }

    public List<Sha256Hash> createBlockFinderBlockHashes() throws DatabaseException {
        final BlockDatabaseManager blockDatabaseManager = new BlockDatabaseManager(_databaseConnection, _databaseManagerCache);

        final BlockId headBlockId = blockDatabaseManager.getHeadBlockId();
        final BlockChainSegmentId headBlockChainSegmentId = blockDatabaseManager.getBlockChainSegmentId(headBlockId);
        final Long maxBlockHeight = blockDatabaseManager.getBlockHeightForBlockId(headBlockId);

        final MutableList<Sha256Hash> blockHashes = new MutableList<Sha256Hash>(BitcoinUtil.log2(maxBlockHeight.intValue() + 10));
        int blockHeightStep = 1;
        for (Long blockHeight = maxBlockHeight; blockHeight > 0L; blockHeight -= blockHeightStep) {
            final BlockId blockId = blockDatabaseManager.getBlockIdAtHeight(headBlockChainSegmentId, blockHeight);
            final Sha256Hash blockHash = blockDatabaseManager.getBlockHashFromId(blockId);

            blockHashes.add(blockHash);

            if (blockHashes.getSize() >= 10) {
                blockHeightStep *= 2;
            }
        }

        return blockHashes;
    }
}
