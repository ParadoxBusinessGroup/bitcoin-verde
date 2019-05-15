package com.softwareverde.bitcoin.server.database.cache;

import com.softwareverde.bitcoin.address.AddressId;
import com.softwareverde.bitcoin.block.BlockId;
import com.softwareverde.bitcoin.chain.segment.BlockchainSegmentId;
import com.softwareverde.bitcoin.hash.sha256.ImmutableSha256Hash;
import com.softwareverde.bitcoin.hash.sha256.Sha256Hash;
import com.softwareverde.bitcoin.server.database.cache.conscientious.DisabledUnspentTransactionOutputCache;
import com.softwareverde.bitcoin.server.database.cache.utxo.NativeUnspentTransactionOutputCache;
import com.softwareverde.bitcoin.server.database.cache.utxo.UnspentTransactionOutputCache;
import com.softwareverde.bitcoin.server.database.cache.utxo.UtxoCount;
import com.softwareverde.bitcoin.transaction.ImmutableTransaction;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionId;
import com.softwareverde.bitcoin.transaction.output.TransactionOutputId;
import com.softwareverde.bitcoin.transaction.output.identifier.TransactionOutputIdentifier;
import com.softwareverde.constable.list.List;

public class LocalDatabaseManagerCache implements DatabaseManagerCache {

    public LocalDatabaseManagerCache(final UtxoCount maxUtxoCount) {
        if (NativeUnspentTransactionOutputCache.isEnabled()) {
            _unspentTransactionOutputCache = new NativeUnspentTransactionOutputCache(maxUtxoCount);
        }
        else {
            _unspentTransactionOutputCache = new DisabledUnspentTransactionOutputCache(); // MemoryConscientiousCache.wrap(0.95F, new JvmUnspentTransactionOutputCache());
        }
    }

    public LocalDatabaseManagerCache(final MasterDatabaseManagerCache masterCache) {
        final UtxoCount maxUtxoCount = masterCache.getMaxCachedUtxoCount();
        if (NativeUnspentTransactionOutputCache.isEnabled()) {
            _unspentTransactionOutputCache = new NativeUnspentTransactionOutputCache(maxUtxoCount);
        }
        else {
            _unspentTransactionOutputCache = new DisabledUnspentTransactionOutputCache(); // MemoryConscientiousCache.wrap(0.95F, new JvmUnspentTransactionOutputCache());
        }

        _transactionIdCache.setMasterCache(masterCache.getTransactionIdCache());
        _transactionCache.setMasterCache(masterCache.getTransactionCache());
        _transactionOutputIdCache.setMasterCache(masterCache.getTransactionOutputIdCache());
        _blockIdBlockchainSegmentIdCache.setMasterCache(masterCache.getBlockIdBlockchainSegmentIdCache());
        _addressIdCache.setMasterCache(masterCache.getAddressIdCache());
        _blockHeightCache.setMasterCache(masterCache.getBlockHeightCache());
        _unspentTransactionOutputCache.setMasterCache(masterCache.getUnspentTransactionOutputCache());
    }

    @Override
    public void log() {
        _transactionIdCache.debug();
        _transactionCache.debug();
        _transactionOutputIdCache.debug();
        _blockIdBlockchainSegmentIdCache.debug();
        _addressIdCache.debug();
    }

    @Override
    public void resetLog() {
        _transactionIdCache.resetDebug();
        _transactionCache.resetDebug();
        _transactionOutputIdCache.resetDebug();
        _blockIdBlockchainSegmentIdCache.resetDebug();
        _addressIdCache.resetDebug();
    }


    // TRANSACTION ID CACHE --------------------------------------------------------------------------------------------

    protected final HashMapCache<ImmutableSha256Hash, TransactionId> _transactionIdCache = new HashMapCache<>("TransactionIdCache", HashMapCache.DEFAULT_CACHE_SIZE);

    @Override
    public void cacheTransactionId(final ImmutableSha256Hash transactionHash, final TransactionId transactionId) {
        _transactionIdCache.cacheItem(transactionHash, transactionId);
    }

    @Override
    public TransactionId getCachedTransactionId(final ImmutableSha256Hash transactionHash) {
        return _transactionIdCache.getCachedItem(transactionHash);
    }

    @Override
    public void invalidateTransactionIdCache() {
        _transactionIdCache.invalidate();
    }

    public HashMapCache<ImmutableSha256Hash, TransactionId> getTransactionIdCache() { return _transactionIdCache; }

    // -----------------------------------------------------------------------------------------------------------------


    // TRANSACTION CACHE -----------------------------------------------------------------------------------------------

    protected final HashMapCache<TransactionId, ImmutableTransaction> _transactionCache = new HashMapCache<>("TransactionCache", HashMapCache.DEFAULT_CACHE_SIZE);

    @Override
    public void cacheTransaction(final TransactionId transactionId, final ImmutableTransaction transaction) {
        _transactionCache.cacheItem(transactionId, transaction);
    }

    @Override
    public Transaction getCachedTransaction(final TransactionId transactionId) {
        return _transactionCache.getCachedItem(transactionId);
    }

    @Override
    public void invalidateTransactionCache() {
        _transactionCache.invalidate();
    }

    public HashMapCache<TransactionId, ImmutableTransaction> getTransactionCache() { return _transactionCache; }

    // -----------------------------------------------------------------------------------------------------------------

    // TRANSACTION OUTPUT ID CACHE -------------------------------------------------------------------------------------

    protected final HashMapCache<CachedTransactionOutputIdentifier, TransactionOutputId> _transactionOutputIdCache = new HashMapCache<>("TransactionOutputId", HashMapCache.DISABLED_CACHE_SIZE);

    @Override
    public void cacheTransactionOutputId(final TransactionId transactionId, final Integer transactionOutputIndex, final TransactionOutputId transactionOutputId) {
        final CachedTransactionOutputIdentifier cachedTransactionOutputIdentifier = new CachedTransactionOutputIdentifier(transactionId, transactionOutputIndex);
        _transactionOutputIdCache.cacheItem(cachedTransactionOutputIdentifier, transactionOutputId);
    }

    @Override
    public TransactionOutputId getCachedTransactionOutputId(final TransactionId transactionId, final Integer transactionOutputIndex) {
        final CachedTransactionOutputIdentifier cachedTransactionOutputIdentifier = new CachedTransactionOutputIdentifier(transactionId, transactionOutputIndex);
        return _transactionOutputIdCache.getCachedItem(cachedTransactionOutputIdentifier);
    }

    @Override
    public void invalidateTransactionOutputIdCache() {
        _transactionOutputIdCache.invalidate();
    }

    public HashMapCache<CachedTransactionOutputIdentifier, TransactionOutputId> getTransactionOutputIdCache() { return _transactionOutputIdCache; }

    // -----------------------------------------------------------------------------------------------------------------

    // BLOCK BLOCK CHAIN SEGMENT ID CACHE ------------------------------------------------------------------------------

    protected final HashMapCache<BlockId, BlockchainSegmentId> _blockIdBlockchainSegmentIdCache = new HashMapCache<>("BlockId-BlockchainSegmentId", 1460);

    @Override
    public void cacheBlockchainSegmentId(final BlockId blockId, final BlockchainSegmentId blockchainSegmentId) {
        _blockIdBlockchainSegmentIdCache.cacheItem(blockId, blockchainSegmentId);
    }

    @Override
    public BlockchainSegmentId getCachedBlockchainSegmentId(final BlockId blockId) {
        return _blockIdBlockchainSegmentIdCache.getCachedItem(blockId);
    }

    @Override
    public void invalidateBlockIdBlockchainSegmentIdCache() {
        _blockIdBlockchainSegmentIdCache.invalidate();
    }

    public HashMapCache<BlockId, BlockchainSegmentId> getBlockIdBlockchainSegmentIdCache() { return _blockIdBlockchainSegmentIdCache; }

    // -----------------------------------------------------------------------------------------------------------------

    // ADDRESS ID CACHE ------------------------------------------------------------------------------------------------

    protected final HashMapCache<String, AddressId> _addressIdCache = new HashMapCache<>("AddressId", HashMapCache.DISABLED_CACHE_SIZE);

    @Override
    public void cacheAddressId(final String address, final AddressId addressId) {
        _addressIdCache.cacheItem(address, addressId);
    }

    @Override
    public AddressId getCachedAddressId(final String address) {
        return _addressIdCache.getCachedItem(address);
    }

    @Override
    public void invalidateAddressIdCache() {
        _addressIdCache.invalidate();
    }

    public HashMapCache<String, AddressId> getAddressIdCache() { return _addressIdCache; }

    // -----------------------------------------------------------------------------------------------------------------

    // BLOCK HEIGHT CACHE ----------------------------------------------------------------------------------------------

    protected final HashMapCache<BlockId, Long> _blockHeightCache = new HashMapCache<>("BlockHeight", 500000);

    @Override
    public void cacheBlockHeight(final BlockId blockId, final Long blockHeight) {
        _blockHeightCache.cacheItem(blockId, blockHeight);
    }

    @Override
    public Long getCachedBlockHeight(final BlockId blockId) {
        return _blockHeightCache.getCachedItem(blockId);
    }

    @Override
    public void invalidateBlockHeaderCache() {
        _blockHeightCache.invalidate();
    }

    public HashMapCache<BlockId, Long> getBlockHeightCache() { return _blockHeightCache; }

    // -----------------------------------------------------------------------------------------------------------------

    // UNSPENT TRANSACTION OUTPUT CACHE --------------------------------------------------------------------------------

    protected final UnspentTransactionOutputCache _unspentTransactionOutputCache;

    @Override
    public void cacheUnspentTransactionOutputId(final Sha256Hash transactionHash, final Integer transactionOutputIndex, final TransactionOutputId transactionOutputId) {
        _unspentTransactionOutputCache.cacheUnspentTransactionOutputId(transactionHash, transactionOutputIndex, transactionOutputId);
    }

    @Override
    public TransactionOutputId getCachedUnspentTransactionOutputId(final Sha256Hash transactionHash, final Integer transactionOutputIndex) {
        return _unspentTransactionOutputCache.getCachedUnspentTransactionOutputId(transactionHash, transactionOutputIndex);
    }

    @Override
    public void invalidateUnspentTransactionOutputId(final TransactionOutputIdentifier transactionOutputId) {
        _unspentTransactionOutputCache.invalidateUnspentTransactionOutputId(transactionOutputId);
    }

    @Override
    public void invalidateUnspentTransactionOutputIds(final List<TransactionOutputIdentifier> transactionOutputIds) {
        _unspentTransactionOutputCache.invalidateUnspentTransactionOutputIds(transactionOutputIds);
    }

    public UnspentTransactionOutputCache getUnspentTransactionOutputCache() { return _unspentTransactionOutputCache; }

    // -----------------------------------------------------------------------------------------------------------------

    @Override
    public void close() {
        _unspentTransactionOutputCache.close();
    }

}
