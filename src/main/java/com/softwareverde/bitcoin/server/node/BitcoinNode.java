package com.softwareverde.bitcoin.server.node;

import com.softwareverde.bitcoin.block.Block;
import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessage;
import com.softwareverde.bitcoin.server.message.BitcoinProtocolMessageInflater;
import com.softwareverde.bitcoin.server.message.type.MessageType;
import com.softwareverde.bitcoin.server.message.type.block.BlockMessage;
import com.softwareverde.bitcoin.server.message.type.error.ErrorMessage;
import com.softwareverde.bitcoin.server.message.type.node.address.BitcoinNodeIpAddress;
import com.softwareverde.bitcoin.server.message.type.node.address.BitcoinNodeIpAddressMessage;
import com.softwareverde.bitcoin.server.message.type.node.feature.NodeFeatures;
import com.softwareverde.bitcoin.server.message.type.node.ping.BitcoinPingMessage;
import com.softwareverde.bitcoin.server.message.type.node.pong.BitcoinPongMessage;
import com.softwareverde.bitcoin.server.message.type.query.block.QueryBlocksMessage;
import com.softwareverde.bitcoin.server.message.type.query.response.QueryResponseMessage;
import com.softwareverde.bitcoin.server.message.type.query.response.hash.DataHash;
import com.softwareverde.bitcoin.server.message.type.query.response.hash.DataHashType;
import com.softwareverde.bitcoin.server.message.type.request.RequestDataMessage;
import com.softwareverde.bitcoin.server.message.type.version.acknowledge.BitcoinAcknowledgeVersionMessage;
import com.softwareverde.bitcoin.server.message.type.version.synchronize.BitcoinSynchronizeVersionMessage;
import com.softwareverde.bitcoin.type.callback.Callback;
import com.softwareverde.bitcoin.type.hash.sha256.Sha256Hash;
import com.softwareverde.io.Logger;
import com.softwareverde.network.ip.Ipv4;
import com.softwareverde.network.p2p.message.ProtocolMessage;
import com.softwareverde.network.p2p.message.type.PingMessage;
import com.softwareverde.network.p2p.message.type.PongMessage;
import com.softwareverde.network.p2p.message.type.SynchronizeVersionMessage;
import com.softwareverde.network.p2p.node.Node;
import com.softwareverde.network.p2p.node.NodeConnection;
import com.softwareverde.network.socket.BinaryPacketFormat;
import com.softwareverde.util.HexUtil;
import com.softwareverde.util.Util;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class BitcoinNode extends Node {
    public interface QueryCallback extends Callback<List<Sha256Hash>> { }
    public interface DownloadBlockCallback extends Callback<Block> { }

    protected static class BlockHashQueryCallback implements Callback<List<Sha256Hash>> {
        public Sha256Hash afterBlockHash;
        public QueryCallback callback;

        public BlockHashQueryCallback(final Sha256Hash afterBlockHash, final QueryCallback callback) {
            this.afterBlockHash = afterBlockHash;
            this.callback = callback;
        }

        @Override
        public void onResult(final List<Sha256Hash> result) {
            this.callback.onResult(result);
        }
    }

    protected static <U, T, S extends Callback<U>> void _executeAndClearCallbacks(final Map<T, Set<S>> callbackMap, final T key, final U value) {
        final Set<S> callbackSet = callbackMap.remove(key);
        if (callbackSet == null) { return; }

        for (final S callback : callbackSet) {
            callback.onResult(value);
        }
    }

    protected final Map<DataHashType, Set<BlockHashQueryCallback>> _queryRequests = new HashMap<DataHashType, Set<BlockHashQueryCallback>>();
    protected final Map<Sha256Hash, Set<DownloadBlockCallback>> _downloadBlockRequests = new HashMap<Sha256Hash, Set<DownloadBlockCallback>>();

    protected final Map<DataHashType, Set<DataHash>> _availableDataHashes = new HashMap<DataHashType, Set<DataHash>>();

    @Override
    protected BitcoinPingMessage _createPingMessage() {
        return new BitcoinPingMessage();
    }

    @Override
    protected BitcoinPongMessage _createPongMessage(final PingMessage pingMessage) {
        final BitcoinPongMessage pongMessage = new BitcoinPongMessage();
        pongMessage.setNonce(pingMessage.getNonce());
        return pongMessage;
    }

    @Override
    protected BitcoinSynchronizeVersionMessage _createSynchronizeVersionMessage() {
        final BitcoinSynchronizeVersionMessage synchronizeVersionMessage = new BitcoinSynchronizeVersionMessage();
        { // Set Remote NodeIpAddress...
            final BitcoinNodeIpAddress remoteNodeIpAddress = new BitcoinNodeIpAddress();
            remoteNodeIpAddress.setIp(Ipv4.parse(_connection.getRemoteIp()));
            remoteNodeIpAddress.setPort(_connection.getPort());
            remoteNodeIpAddress.setNodeFeatures(new NodeFeatures());
            synchronizeVersionMessage.setRemoteAddress(remoteNodeIpAddress);
        }
        return synchronizeVersionMessage;
    }

    @Override
    protected BitcoinAcknowledgeVersionMessage _createAcknowledgeVersionMessage(final SynchronizeVersionMessage synchronizeVersionMessage) {
        return new BitcoinAcknowledgeVersionMessage();
    }

    @Override
    protected BitcoinNodeIpAddressMessage _createNodeIpAddressMessage() {
        return new BitcoinNodeIpAddressMessage();
    }

    public BitcoinNode(final String host, final Integer port) {
        super(host, port, BitcoinProtocolMessage.BINARY_PACKET_FORMAT);

        _connection.setMessageReceivedCallback(new NodeConnection.MessageReceivedCallback() {
            @Override
            public void onMessageReceived(final ProtocolMessage protocolMessage) {
                if (! (protocolMessage instanceof BitcoinProtocolMessage)) {
                    Logger.log("NOTICE: Disregarding Non-Bitcoin ProtocolMessage.");
                    return;
                }

                final BitcoinProtocolMessage message = (BitcoinProtocolMessage) protocolMessage;

                _lastMessageReceivedTimestamp = System.currentTimeMillis();

                switch (message.getCommand()) {
                    case PING: {
                        _onPingReceived((BitcoinPingMessage) message);
                    } break;
                    case PONG: {
                        _onPongReceived((PongMessage) message);
                    } break;
                    case SYNCHRONIZE_VERSION: {
                        _onSynchronizeVersion((BitcoinSynchronizeVersionMessage) message);
                    } break;
                    case ACKNOWLEDGE_VERSION: {
                        _onAcknowledgeVersionMessageReceived((BitcoinAcknowledgeVersionMessage) message);
                    } break;
                    case NODE_ADDRESSES: {
                        _onNodeAddressesReceived((BitcoinNodeIpAddressMessage) message);
                    } break;
                    case ERROR: {
                        _onErrorMessageReceived((ErrorMessage) message);
                    } break;
                    case QUERY_RESPONSE: {
                        _onQueryResponseMessageReceived((QueryResponseMessage) message);
                    } break;
                    case BLOCK: {
                        _onBlockMessageReceived((BlockMessage) message);
                    } break;
                    default: {
                        Logger.log("NOTICE: Unhandled Message Command: "+ message.getCommand() +": 0x"+ HexUtil.toHexString(message.getHeaderBytes()));
                    } break;
                }
            }
        });

        _connection.setOnConnectCallback(new Runnable() {
            @Override
            public void run() {
                _onConnect();
            }
        });

        _connection.setOnDisconnectCallback(new Runnable() {
            @Override
            public void run() {
                _onDisconnect();
            }
        });

        _connection.startConnectionThread();
    }

    protected void _onErrorMessageReceived(final ErrorMessage errorMessage) {
        final ErrorMessage.RejectCode rejectCode = errorMessage.getRejectCode();
        Logger.log("RECEIVED ERROR:"+ rejectCode.getRejectMessageType().getValue() +" "+ HexUtil.toHexString(new byte[] { rejectCode.getCode() }) +" "+ errorMessage.getRejectDescription() +" "+ HexUtil.toHexString(errorMessage.getExtraData()));
    }

    protected void _onQueryResponseMessageReceived(final QueryResponseMessage queryResponseMessage) {
        final Map<DataHashType, List<Sha256Hash>> dataHashesMap = new HashMap<DataHashType, List<Sha256Hash>>();

        final List<DataHash> dataHashes = queryResponseMessage.getDataHashes();
        for (final DataHash dataHash : dataHashes) {
            final DataHashType dataHashType = dataHash.getDataHashType();
            _storeInMapSet(_availableDataHashes, dataHashType, dataHash);
            _storeInMapList(dataHashesMap, dataHashType, dataHash.getObjectHash());
        }

        for (final DataHashType dataHashType : dataHashesMap.keySet()) {
            final List<Sha256Hash> objectHashes = dataHashesMap.get(dataHashType);
            if (objectHashes.isEmpty()) { continue; }

            {   // NOTE: Since the QueryResponseMessage is not tied to the QueryRequest for Blocks,
                //  so in order to tie the callback to the response, the first block within the response is requested.
                //  If the downloaded Block's previousBlockHash matchesByte the requestAfter BlockHash, then the response is
                //  assumed to be for that callback's request.

                if (dataHashType == DataHashType.BLOCK) {
                    final Sha256Hash blockHash = objectHashes.get(0);
                    _storeInMapSet(_downloadBlockRequests, blockHash, new DownloadBlockCallback() {
                        @Override
                        public void onResult(final Block block) {
                            final Set<BlockHashQueryCallback> blockHashQueryCallbackSet = _queryRequests.get(dataHashType);
                            if (blockHashQueryCallbackSet == null) { return; }

                            for (final BlockHashQueryCallback blockHashQueryCallback : Util.copySet(blockHashQueryCallbackSet)) {
                                if (block.getPreviousBlockHash().equals(blockHashQueryCallback.afterBlockHash)) {
                                    blockHashQueryCallbackSet.remove(blockHashQueryCallback);
                                    blockHashQueryCallback.onResult(objectHashes);
                                }
                            }
                        }
                    });
                    _requestBlock(blockHash); // TODO: Convert to _requestBlockHeader(blockHash);

                    continue;
                }
            }

            _executeAndClearCallbacks(_queryRequests, dataHashType, objectHashes);
        }
    }

    protected void _onBlockMessageReceived(final BlockMessage blockMessage) {
        final Block block = blockMessage.getBlock();
        final Boolean blockHeaderIsValid = block.isValid();

        final Sha256Hash blockHash = block.getHash();
        _executeAndClearCallbacks(_downloadBlockRequests, blockHash, (blockHeaderIsValid ? block : null));
    }

    protected void _queryForBlockHashesAfter(final Sha256Hash blockHash) {
        final QueryBlocksMessage queryBlocksMessage = new QueryBlocksMessage();
        queryBlocksMessage.addBlockHeaderHash(blockHash);
        _queueMessage(queryBlocksMessage);
    }

    protected void _requestBlock(final Sha256Hash blockHash) {
        final RequestDataMessage requestDataMessage = new RequestDataMessage();
        requestDataMessage.addInventoryItem(new DataHash(DataHashType.BLOCK, blockHash));
        _queueMessage(requestDataMessage);
    }

    public void requestBlockHashesAfter(final Sha256Hash blockHash, final QueryCallback queryCallback) {
        _storeInMapSet(_queryRequests, DataHashType.BLOCK, new BlockHashQueryCallback(blockHash, queryCallback));
        _queryForBlockHashesAfter(blockHash);
    }

    public void requestBlock(final Sha256Hash blockHash, final DownloadBlockCallback downloadBlockCallback) {
        _storeInMapSet(_downloadBlockRequests, blockHash, downloadBlockCallback);
        _requestBlock(blockHash);
    }

    @Override
    public BitcoinNodeIpAddress getNodeAddress() {
        return (BitcoinNodeIpAddress) _nodeIpAddress;
    }

    @Override
    public void disconnect() {
        super.disconnect();

        _availableDataHashes.clear();
        _queryRequests.clear();
        _downloadBlockRequests.clear();
    }
}
