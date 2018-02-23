package com.softwareverde.bitcoin.transaction.output;

import com.softwareverde.bitcoin.transaction.script.MutableScript;
import com.softwareverde.bitcoin.util.bytearray.ByteArrayReader;
import com.softwareverde.bitcoin.util.bytearray.Endian;

public class TransactionOutputInflater {
    protected TransactionOutput _fromByteArrayReader(final Integer index, final ByteArrayReader byteArrayReader) {
        final MutableTransactionOutput transactionOutput = new MutableTransactionOutput();

        transactionOutput._amount = byteArrayReader.readLong(8, Endian.LITTLE);
        transactionOutput._index = index;

        final Integer scriptByteCount = byteArrayReader.readVariableSizedInteger().intValue();
        transactionOutput._lockingScript = new MutableScript(byteArrayReader.readBytes(scriptByteCount, Endian.LITTLE));

        if (byteArrayReader.wentOutOfBounds()) { return null; }

        return transactionOutput;
    }

    public TransactionOutput fromBytes(final Integer index, final ByteArrayReader byteArrayReader) {
        return _fromByteArrayReader(index, byteArrayReader);
    }

    public TransactionOutput fromBytes(final Integer index, final byte[] bytes) {
        final ByteArrayReader byteArrayReader = new ByteArrayReader(bytes);
        return _fromByteArrayReader(index, byteArrayReader);
    }
}
