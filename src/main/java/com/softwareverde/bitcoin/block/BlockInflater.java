package com.softwareverde.bitcoin.block;

import com.softwareverde.bitcoin.block.header.BlockHeader;
import com.softwareverde.bitcoin.block.header.BlockHeaderInflater;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionInflater;
import com.softwareverde.bitcoin.util.bytearray.ByteArrayReader;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.list.mutable.MutableList;

public class BlockInflater {
    public static final Integer MAX_TRANSACTION_COUNT = Integer.MAX_VALUE; // TODO: Set to the the current consensus value...

    protected MutableBlock _fromByteArrayReader(final ByteArrayReader byteArrayReader) {
        final BlockHeaderInflater blockHeaderInflater = new BlockHeaderInflater();
        final TransactionInflater transactionInflater = new TransactionInflater();

        final BlockHeader blockHeader = blockHeaderInflater.fromBytes(byteArrayReader);
        if (blockHeader == null) { return null; }

        final Integer transactionCount = byteArrayReader.readVariableSizedInteger().intValue();
        if (transactionCount > MAX_TRANSACTION_COUNT) { return null; }

        final MutableList<Transaction> transactions = new MutableList<>(transactionCount);

        for (int i = 0; i < transactionCount; ++i) {
            final Transaction transaction = transactionInflater.fromBytes(byteArrayReader);
            if (transaction == null) { return null; }

            transactions.add(transaction);
        }

        if (byteArrayReader.didOverflow()) { return null; }

        return new MutableBlock(blockHeader, transactions);
    }

    public MutableBlock fromBytes(final ByteArrayReader byteArrayReader) {
        if (byteArrayReader == null) { return null; }

        return _fromByteArrayReader(byteArrayReader);
    }

    public MutableBlock fromBytes(final ByteArray byteArrayReader) {
        if (byteArrayReader == null) { return null; }

        return _fromByteArrayReader(new ByteArrayReader(byteArrayReader));
    }

    public MutableBlock fromBytes(final byte[] bytes) {
        if (bytes == null) { return null; }

        final ByteArrayReader byteArrayReader = new ByteArrayReader(bytes);
        return _fromByteArrayReader(byteArrayReader);
    }
}
