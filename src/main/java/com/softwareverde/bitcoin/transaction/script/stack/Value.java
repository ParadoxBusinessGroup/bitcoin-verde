package com.softwareverde.bitcoin.transaction.script.stack;

import com.softwareverde.bitcoin.secp256k1.key.PublicKey;
import com.softwareverde.bitcoin.transaction.locktime.ImmutableLockTime;
import com.softwareverde.bitcoin.transaction.locktime.ImmutableSequenceNumber;
import com.softwareverde.bitcoin.transaction.locktime.LockTime;
import com.softwareverde.bitcoin.transaction.locktime.SequenceNumber;
import com.softwareverde.bitcoin.transaction.script.signature.ScriptSignature;
import com.softwareverde.bitcoin.util.BitcoinUtil;
import com.softwareverde.constable.Const;
import com.softwareverde.constable.bytearray.ImmutableByteArray;
import com.softwareverde.util.ByteUtil;
import com.softwareverde.util.StringUtil;

public class Value extends ImmutableByteArray implements Const {
    private static final Integer MAX_BYTE_COUNT = 520; // https://en.bitcoin.it/wiki/Script#Arithmetic

    // NOTE: Bitcoin uses "MPI" encoding for its numeric values on the stack.
    //  This fact and/or a specification for how MPI is encoded is not on the wiki (...of course).
    //  It appears MPI is a minimum-byte-encoding, with a sign bit if negative, similar(ish) to DER encoding.
    //  As an exception, Zero is encoded as zero bytes... Not sure why.
    //  Ex: -65280
    //      MPI:            0x80FF00
    //      Signed Hex:     -0xFF00
    //      2's Complement: 0xFFFFFFFFFFFF0100
    // The returned byte array is little-endian.
    protected static byte[] _longToBytes(final Long value) {
        if (value == 0L) { return new byte[0]; }

        final boolean isNegative = (value < 0);

        final long absValue = Math.abs(value);
        final int unsignedByteCount = ( (BitcoinUtil.log2((int) absValue) / 8) + 1 );
        final byte[] absValueBytes = ByteUtil.integerToBytes(absValue);

        final boolean requiresSignPadding = ((absValueBytes[absValueBytes.length - unsignedByteCount] & 0x80) == 0x80);

        final byte[] bytes = new byte[(requiresSignPadding ? unsignedByteCount + 1 : unsignedByteCount)];
        ByteUtil.setBytes(bytes, ByteUtil.reverseEndian(absValueBytes));
        if (isNegative) {
            bytes[bytes.length - 1] |= (byte) 0x80;
        }

        return bytes;
    }

    public static Value fromInteger(final Long longValue) {
        final byte[] bytes = _longToBytes(longValue);
        return new Value(bytes);
    }

    public static Value fromBoolean(final Boolean booleanValue) {
        final byte[] bytes = _longToBytes(booleanValue ? 1L : 0L);
        return new Value(bytes);
    }

    public static Value fromBytes(final byte[] bytes) {
        if (bytes.length > MAX_BYTE_COUNT) { return null; }
        return new Value(bytes);
    }

    protected static boolean _isNegativeNumber(final byte[] bytes) {
        final byte mostSignificantByte = bytes[0];
        return ( (mostSignificantByte & ((byte) 0x80)) != ((byte) 0x00) );
    }

    protected Integer _asInteger() {
        if (_bytes.length == 0) { return 0; }

        final byte[] bigEndianBytes = ByteUtil.reverseEndian(_bytes);

        final boolean isNegative = _isNegativeNumber(bigEndianBytes);

        { // Remove the sign bit... (only matters when _bytes.length is less than the byteCount of an integer)
            bigEndianBytes[0] &= (byte) 0x7F;
        }

        final Integer value = ByteUtil.bytesToInteger(bigEndianBytes);
        return (isNegative ? -value : value);
    }

    protected Long _asLong() {
        if (_bytes.length == 0) { return 0L; }

        final byte[] bigEndianBytes = ByteUtil.reverseEndian(_bytes);

        final boolean isNegative = _isNegativeNumber(bigEndianBytes);

        { // Remove the sign bit... (only matters when _bytes.length is less than the byteCount of a long)
            bigEndianBytes[0] &= (byte) 0x7F;
        }

        final Long value = ByteUtil.bytesToLong(bigEndianBytes);
        return (isNegative ? -value : value);
    }

    protected Boolean _asBoolean() {
        if (_bytes.length == 0) { return false; }

        for (int i=0; i<_bytes.length; ++i) {
            if ( (i == (_bytes.length - 1)) && (_bytes[i] == (byte) 0x80) ) { continue; } // Negative zero can still be false... (Little-Endian)
            if (_bytes[i] != 0x00) { return true; }
        }
        return false;
    }

    protected Value(final byte[] bytes) {
        super(bytes);
    }

    /**
     * Interprets _bytes as a signed, little-endian, variable-length integer value.
     * If _bytes is empty, zero is returned.
     */
    public Integer asInteger() {
        return _asInteger();
    }

    public Long asLong() {
        return _asLong();
    }

    public Boolean asBoolean() {
        return _asBoolean();
    }

    public Boolean isMinimallyEncodedInteger() {
        final Integer asInteger = _asInteger();
        final byte[] minimallyEncodedBytes = _longToBytes(asInteger.longValue());
        return ByteUtil.areEqual(minimallyEncodedBytes, _bytes);
    }

    public Boolean isMinimallyEncodedLong() {
        final Long asLong = _asLong();
        final byte[] minimallyEncodedBytes = _longToBytes(asLong);
        return ByteUtil.areEqual(minimallyEncodedBytes, _bytes);
    }

    public LockTime asLockTime() {
        return new ImmutableLockTime(ByteUtil.bytesToLong(ByteUtil.reverseEndian(_bytes)));
    }

    public SequenceNumber asSequenceNumber() {
        return new ImmutableSequenceNumber(ByteUtil.bytesToLong(ByteUtil.reverseEndian(_bytes)));
    }

    public ScriptSignature asScriptSignature() {
        return ScriptSignature.fromBytes(this);
    }

    public PublicKey asPublicKey() {
        return PublicKey.fromBytes(this);
    }

    public String asString() {
        return StringUtil.bytesToString(_bytes); // UTF-8
    }

    @Override
    public Value asConst() {
        return this;
    }
}
