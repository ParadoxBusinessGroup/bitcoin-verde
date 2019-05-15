package com.softwareverde.bitcoin.server.message.type.thin.block;

import com.softwareverde.bitcoin.block.BlockInflater;
import com.softwareverde.bitcoin.block.header.BlockHeader;
import com.softwareverde.bitcoin.block.header.BlockHeaderInflater;
import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessageInflater;
import com.softwareverde.bitcoin.server.message.header.BitcoinProtocolMessageHeader;
import com.softwareverde.bitcoin.server.message.type.MessageType;
import com.softwareverde.bitcoin.transaction.Transaction;
import com.softwareverde.bitcoin.transaction.TransactionInflater;
import com.softwareverde.bitcoin.util.bytearray.ByteArrayReader;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.util.bytearray.Endian;

public class ExtraThinBlockMessageInflater extends BitcoinProtocolMessageInflater {
    @Override
    public ExtraThinBlockMessage fromBytes(final byte[] bytes) {
        final BlockHeaderInflater blockHeaderInflater = new BlockHeaderInflater();
        final TransactionInflater transactionInflater = new TransactionInflater();

        final ExtraThinBlockMessage extraThinBlockMessage = new ExtraThinBlockMessage();
        final ByteArrayReader byteArrayReader = new ByteArrayReader(bytes);

        final BitcoinProtocolMessageHeader protocolMessageHeader = _parseHeader(byteArrayReader, MessageType.EXTRA_THIN_BLOCK);
        if (protocolMessageHeader == null) { return null; }

        final BlockHeader blockHeader = blockHeaderInflater.fromBytes(byteArrayReader);
        extraThinBlockMessage.setBlockHeader(blockHeader);

        final Integer transactionCount = byteArrayReader.readVariableSizedInteger().intValue();
        if (transactionCount > BlockInflater.MAX_TRANSACTION_COUNT) { return null; }

        final ImmutableListBuilder<ByteArray> transactionShortHashesListBuilder = new ImmutableListBuilder<>(transactionCount);
        for (int i = 0; i < transactionCount; ++i) {
            final ByteArray transactionShortHash = MutableByteArray.wrap(byteArrayReader.readBytes(4, Endian.LITTLE));
            transactionShortHashesListBuilder.add(transactionShortHash);
        }
        extraThinBlockMessage.setTransactionHashes(transactionShortHashesListBuilder.build());

        final Integer missingTransactionCount = byteArrayReader.readVariableSizedInteger().intValue();
        if (missingTransactionCount > transactionCount) { return null; }

        final ImmutableListBuilder<Transaction> missingTransactionsListBuilder = new ImmutableListBuilder<>(missingTransactionCount);
        for (int i = 0; i < missingTransactionCount; ++i) {
            final Transaction transaction = transactionInflater.fromBytes(byteArrayReader);
            if (transaction == null) { return null; }
            missingTransactionsListBuilder.add(transaction);
        }
        extraThinBlockMessage.setMissingTransactions(missingTransactionsListBuilder.build());

        if (byteArrayReader.didOverflow()) { return null; }

        return extraThinBlockMessage;
    }
}
