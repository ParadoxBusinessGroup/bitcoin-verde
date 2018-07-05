package com.softwareverde.bitcoin.server.message.type;

import com.softwareverde.bitcoin.util.ByteUtil;

public enum MessageType {
    SYNCHRONIZE_VERSION("version"), ACKNOWLEDGE_VERSION("verack"),
    PING("ping"), PONG("pong"),
    NODE_ADDRESSES("addr"),
    QUERY_BLOCK_HEADERS("getheaders"), QUERY_BLOCK_HEADERS_RESPONSE("headers"),
    ENABLE_NEW_BLOCKS_VIA_HEADERS("sendheaders"),
    QUERY_BLOCKS("getblocks"), QUERY_RESPONSE("inv"),
    REQUEST_OBJECT("getdata"), BLOCK("block"),
    ERROR("reject");

    public static MessageType fromBytes(final byte[] bytes) {
        for (final MessageType command : MessageType.values()) {
            if (ByteUtil.areEqual(command._bytes, bytes)) {
                return command;
            }
        }
        return null;
    }

    private final byte[] _bytes = new byte[12];
    private final String _value;

    MessageType(final String value) {
        _value = value;
        final byte[] valueBytes = value.getBytes();

        for (int i=0; i<_bytes.length; ++i) {
            _bytes[i] = (i<valueBytes.length ? valueBytes[i] : 0x00);
        }
    }

    public byte[] getBytes() {
        return ByteUtil.copyBytes(_bytes);
    }

    public String getValue() {
        return _value;
    }
}
