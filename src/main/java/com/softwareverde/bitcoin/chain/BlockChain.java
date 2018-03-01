package com.softwareverde.bitcoin.chain;

public class BlockChain {
    protected Long _id;
    protected Long _headBlockId;
    protected Long _tailBlockId;
    protected Long _blockHeight;
    protected Long _blockCount;

    protected BlockChain() { }

    public Long getId() {
        return _id;
    }

    public Long getHeadBlockId() {
        return _headBlockId;
    }

    public Long getTailBlockId() {
        return _tailBlockId;
    }

    public Long getBlockHeight() {
        return _blockHeight;
    }

    public Long getBlockCount() {
        return _blockCount;
    }
}
