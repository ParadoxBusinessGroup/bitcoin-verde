package com.softwareverde.bitcoin.server.message.type.request;

import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessage;
import com.softwareverde.bitcoin.server.message.type.MessageType;
import com.softwareverde.bitcoin.server.message.type.query.response.hash.InventoryItem;
import com.softwareverde.bitcoin.util.ByteUtil;
import com.softwareverde.constable.bytearray.ByteArray;
import com.softwareverde.constable.bytearray.MutableByteArray;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.util.bytearray.ByteArrayBuilder;
import com.softwareverde.util.bytearray.Endian;

public class RequestDataMessage extends BitcoinProtocolMessage {
    public static final Integer MAX_COUNT = 50000;

    private final MutableList<InventoryItem> _inventoryItems = new MutableList<>();

    public RequestDataMessage() {
        super(MessageType.REQUEST_DATA);
    }

    public List<InventoryItem> getInventoryItems() {
        return _inventoryItems;
    }

    public void addInventoryItem(final InventoryItem inventoryItem) {
        if (_inventoryItems.getSize() >= MAX_COUNT) { return; }
        _inventoryItems.add(inventoryItem);
    }

    public void clearInventoryItems() {
        _inventoryItems.clear();
    }

    @Override
    protected ByteArray _getPayload() {
        final ByteArrayBuilder byteArrayBuilder = new ByteArrayBuilder();
        byteArrayBuilder.appendBytes(ByteUtil.variableLengthIntegerToBytes(_inventoryItems.getSize()), Endian.BIG);
        for (final InventoryItem inventoryItem : _inventoryItems) {
            byteArrayBuilder.appendBytes(inventoryItem.getBytes(), Endian.BIG);
        }
        return MutableByteArray.wrap(byteArrayBuilder.build());
    }
}
