package com.softwareverde.network.p2p.node;

import com.softwareverde.concurrent.pool.ThreadPool;
import com.softwareverde.concurrent.pool.ThreadPoolThrottle;
import com.softwareverde.constable.list.List;
import com.softwareverde.io.Logger;
import com.softwareverde.network.ip.Ip;
import com.softwareverde.network.p2p.message.ProtocolMessage;
import com.softwareverde.network.p2p.message.type.*;
import com.softwareverde.network.p2p.node.address.NodeIpAddress;
import com.softwareverde.network.socket.BinaryPacketFormat;
import com.softwareverde.network.socket.BinarySocket;
import com.softwareverde.util.CircleBuffer;
import com.softwareverde.util.RotatingQueue;
import com.softwareverde.util.Util;
import com.softwareverde.util.type.time.SystemTime;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public abstract class Node {
    public interface NodeAddressesReceivedCallback { void onNewNodeAddresses(List<NodeIpAddress> nodeIpAddress); }
    public interface NodeConnectedCallback { void onNodeConnected();}
    public interface NodeHandshakeCompleteCallback { void onHandshakeComplete(); }
    public interface NodeDisconnectedCallback { void onNodeDisconnected(); }
    public interface PingCallback { void onResult(Long latency); }

    protected static class PingRequest {
        public final PingCallback pingCallback;
        public final Long timestamp;

        public PingRequest(final PingCallback pingCallback, final Long currentTimeInMilliseconds) {
            this.pingCallback = pingCallback;
            this.timestamp = currentTimeInMilliseconds;
        }
    }

    protected static final RotatingQueue<Long> LOCAL_SYNCHRONIZATION_NONCES = new RotatingQueue<>(32);

    private static final Object NODE_ID_MUTEX = new Object();
    private static Long _nextId = 0L;

    protected final NodeId _id;
    protected final NodeConnection _connection;
    protected final Long _initializationTime;

    protected final SystemTime _systemTime;

    protected NodeIpAddress _localNodeIpAddress = null;
    protected final AtomicBoolean _handshakeHasBeenInvoked = new AtomicBoolean(false);
    protected final AtomicBoolean _synchronizeVersionMessageHasBeenSent = new AtomicBoolean(false);
    protected Boolean _handshakeIsComplete = false;
    protected Long _lastMessageReceivedTimestamp = 0L;
    protected final ConcurrentLinkedQueue<ProtocolMessage> _postHandshakeMessageQueue = new ConcurrentLinkedQueue<>();
    protected Long _networkTimeOffset; // This field is an offset (in milliseconds) that should be added to the local time in order to adjust local SystemTime to this node's NetworkTime...
    protected final AtomicBoolean _hasBeenDisconnected = new AtomicBoolean(false);

    protected final ConcurrentHashMap<Long, PingRequest> _pingRequests = new ConcurrentHashMap<>();

    protected NodeAddressesReceivedCallback _nodeAddressesReceivedCallback = null;
    protected NodeConnectedCallback _nodeConnectedCallback = null;
    protected NodeHandshakeCompleteCallback _nodeHandshakeCompleteCallback = null;
    protected NodeDisconnectedCallback _nodeDisconnectedCallback = null;

    protected final ConcurrentLinkedQueue<Runnable> _postConnectQueue = new ConcurrentLinkedQueue<>();

    protected final ThreadPool _threadPool;

    protected final CircleBuffer<Long> _latencies = new CircleBuffer<>(32);

    protected abstract PingMessage _createPingMessage();
    protected abstract PongMessage _createPongMessage(final PingMessage pingMessage);
    protected abstract SynchronizeVersionMessage _createSynchronizeVersionMessage();
    protected abstract AcknowledgeVersionMessage _createAcknowledgeVersionMessage(SynchronizeVersionMessage synchronizeVersionMessage);
    protected abstract NodeIpAddressMessage _createNodeIpAddressMessage();

    protected final ReentrantReadWriteLock.ReadLock _sendSingleMessageLock;
    protected final ReentrantReadWriteLock.WriteLock _sendMultiMessageLock;

    protected void _queueMessage(final ProtocolMessage message) {
        try {
            _sendSingleMessageLock.lockInterruptibly();
        }
        catch (final InterruptedException exception) { return; }

        try {
            if (_handshakeIsComplete) {
                _connection.queueMessage(message);
            }
            else {
                _postHandshakeMessageQueue.offer(message);
            }
        }
        finally {
            _sendSingleMessageLock.unlock();
        }
    }

    /**
     * Guarantees that the messages are queued in the order provided, and that no other messages are sent between them.
     */
    protected void _queueMessages(final List<? extends ProtocolMessage> messages) {
        try {
            _sendMultiMessageLock.lockInterruptibly();
        }
        catch (final InterruptedException exception) { return; }

        try {
            if (_handshakeIsComplete) {
                for (final ProtocolMessage message : messages) {
                    _connection.queueMessage(message);
                }
            }
            else {
                for (final ProtocolMessage message : messages) {
                    _postHandshakeMessageQueue.offer(message);
                }
            }
        }
        finally {
            _sendMultiMessageLock.unlock();
        }
    }

    protected void _disconnect() {
        if (_hasBeenDisconnected.getAndSet(true)) { return; }

        Logger.log("Socket disconnected. " + "(" + this.getConnectionString() + ")");

        final NodeDisconnectedCallback nodeDisconnectedCallback = _nodeDisconnectedCallback;

        _nodeAddressesReceivedCallback = null;
        _nodeConnectedCallback = null;
        _nodeHandshakeCompleteCallback = null;
        _nodeDisconnectedCallback = null;

        _handshakeIsComplete = false;
        _postHandshakeMessageQueue.clear();

        _pingRequests.clear();

        if (_threadPool instanceof ThreadPoolThrottle) {
            ((ThreadPoolThrottle) _threadPool).stop();
        }

        _connection.setOnDisconnectCallback(null); // Prevent any disconnect callbacks from repeating...
        _connection.cancelConnecting();
        _connection.disconnect();

        if (nodeDisconnectedCallback != null) {
            // Intentionally not using the thread pool since it has been shutdown...
            (new Thread(new Runnable() {
                @Override
                public void run() {
                    nodeDisconnectedCallback.onNodeDisconnected();
                }
            })).start();
        }
    }

    /**
     * Creates a SynchronizeVersion message and enqueues it to the connection.
     *  NOTE: If the connection is not currently alive, this function will not be executed until it has been successfully
     *  connected, otherwise _createSynchronizeVersionMessage() cannot determine the correct remote address.
     */
    protected void _handshake() {
        if (! _handshakeHasBeenInvoked.getAndSet(true)) {
            final Runnable createAndQueueHandshake = new Runnable() {
                @Override
                public void run() {
                    final SynchronizeVersionMessage synchronizeVersionMessage = _createSynchronizeVersionMessage();

                    final Long synchronizationNonce = synchronizeVersionMessage.getNonce();
                    synchronized (LOCAL_SYNCHRONIZATION_NONCES) {
                        LOCAL_SYNCHRONIZATION_NONCES.add(synchronizationNonce);
                    }

                    _connection.queueMessage(synchronizeVersionMessage);

                    synchronized (_synchronizeVersionMessageHasBeenSent) {
                        _synchronizeVersionMessageHasBeenSent.set(true);
                        _synchronizeVersionMessageHasBeenSent.notifyAll();
                    }
                }
            };

            synchronized (_postConnectQueue) {
                if (_connection.isConnected()) {
                    createAndQueueHandshake.run();
                }
                else {
                    _postConnectQueue.offer(createAndQueueHandshake);
                }
            }
        }
    }

    protected void _onConnect() {
        _handshake();

        synchronized (_postConnectQueue) {
            Runnable postConnectRunnable;
            while ((postConnectRunnable = _postConnectQueue.poll()) != null) {
                postConnectRunnable.run();
            }
        }

        if (_nodeConnectedCallback != null) {
            _threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    final NodeConnectedCallback callback = _nodeConnectedCallback;
                    if (callback != null) {
                        callback.onNodeConnected();
                    }
                }
            });
        }
    }

    protected void _ping(final PingCallback pingCallback) {
        final PingMessage pingMessage = _createPingMessage();

        final Long now = _systemTime.getCurrentTimeInMilliSeconds();
        final PingRequest pingRequest = new PingRequest(pingCallback, now);
        _pingRequests.put(pingMessage.getNonce(), pingRequest);
        _queueMessage(pingMessage);
    }

    protected void _onPingReceived(final PingMessage pingMessage) {
        final PongMessage pongMessage = _createPongMessage(pingMessage);
        _queueMessage(pongMessage);
    }

    protected void _onPongReceived(final PongMessage pongMessage) {
        final Long nonce = pongMessage.getNonce();
        final PingRequest pingRequest = _pingRequests.remove(nonce);
        if (pingRequest == null) { return; }

        final PingCallback pingCallback = pingRequest.pingCallback;

        final Long now = _systemTime.getCurrentTimeInMilliSeconds();
        final Long msElapsed = (now - pingRequest.timestamp);

        _latencies.pushItem(msElapsed);

        if (pingCallback != null) {
            _threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    pingCallback.onResult(msElapsed);
                }
            });
        }
    }

    protected void _onSynchronizeVersion(final SynchronizeVersionMessage synchronizeVersionMessage) {
        // TODO: Should probably not accept any node version...

        { // Detect if the connection is to itself...
            final Long remoteNonce = synchronizeVersionMessage.getNonce();
            synchronized (LOCAL_SYNCHRONIZATION_NONCES) {
                for (final Long pastNonce : LOCAL_SYNCHRONIZATION_NONCES) {
                    if (Util.areEqual(pastNonce, remoteNonce)) {
                        Logger.log("Detected connection to self. Disconnecting.");
                        _disconnect();
                        return;
                    }
                }
            }
        }

        { // Calculate the node's network time offset...
            final Long currentTime = _systemTime.getCurrentTimeInSeconds();
            final Long nodeTime = synchronizeVersionMessage.getTimestamp();
            _networkTimeOffset = ((nodeTime - currentTime) * 1000L);
        }

        _localNodeIpAddress = synchronizeVersionMessage.getLocalNodeIpAddress();

        { // Ensure that this node sends its SynchronizeVersion message before the AcknowledgeVersionMessage is transmitted...
            // NOTE: Since  Node::handshake may have been invoked already, it's possible for a race condition between responding to
            //  the SynchronizeVersion here and the other call to handshake.  Therefore, _synchronizeVersionMessageHasBeenSent is
            //  waited on until actually queuing the AcknowledgeVersionMessage.

            _handshake();

            synchronized (_synchronizeVersionMessageHasBeenSent) {
                if (! _synchronizeVersionMessageHasBeenSent.get()) {
                    try { _synchronizeVersionMessageHasBeenSent.wait(10000L); } catch (final Exception exception) { }
                }
            }

            final AcknowledgeVersionMessage acknowledgeVersionMessage = _createAcknowledgeVersionMessage(synchronizeVersionMessage);
            _connection.queueMessage(acknowledgeVersionMessage);
        }
    }

    protected void _onAcknowledgeVersionMessageReceived(final AcknowledgeVersionMessage acknowledgeVersionMessage) {
        _handshakeIsComplete = true;

        _threadPool.execute(new Runnable() {
            @Override
            public void run() {
                final NodeHandshakeCompleteCallback callback = _nodeHandshakeCompleteCallback;
                if (callback != null) {
                    callback.onHandshakeComplete();
                }
            }
        });

        ProtocolMessage protocolMessage;
        while ((protocolMessage = _postHandshakeMessageQueue.poll()) != null) {
            _queueMessage(protocolMessage);
        }
    }

    protected void _onNodeAddressesReceived(final NodeIpAddressMessage nodeIpAddressMessage) {
        final NodeAddressesReceivedCallback nodeAddressesReceivedCallback = _nodeAddressesReceivedCallback;
        final List<NodeIpAddress> nodeIpAddresses = nodeIpAddressMessage.getNodeIpAddresses();
        if (nodeIpAddresses.isEmpty()) { return; }

        if (nodeAddressesReceivedCallback != null) {
            _threadPool.execute(new Runnable() {
                @Override
                public void run() {
                    nodeAddressesReceivedCallback.onNewNodeAddresses(nodeIpAddresses);
                }
            });
        }
    }

    protected void _initConnection() {
        _connection.setOnConnectCallback(new Runnable() {
            @Override
            public void run() {
                _onConnect();
            }
        });

        _connection.setOnDisconnectCallback(new Runnable() {
            @Override
            public void run() {
                _disconnect();
            }
        });
    }

    public Node(final String host, final Integer port, final BinaryPacketFormat binaryPacketFormat, final ThreadPool threadPool) {
        synchronized (NODE_ID_MUTEX) {
            _id = NodeId.wrap(_nextId);
            _nextId += 1;
        }

        _systemTime = new SystemTime();
        _connection = new NodeConnection(host, port, binaryPacketFormat, threadPool);
        _initializationTime = _systemTime.getCurrentTimeInMilliSeconds();
        _threadPool = threadPool;

        final ReentrantReadWriteLock queueMessageLock = new ReentrantReadWriteLock();
        _sendSingleMessageLock = queueMessageLock.readLock();
        _sendMultiMessageLock = queueMessageLock.writeLock();

        _initConnection();
    }

    public Node(final String host, final Integer port, final BinaryPacketFormat binaryPacketFormat, final SystemTime systemTime, final ThreadPool threadPool) {
        synchronized (NODE_ID_MUTEX) {
            _id = NodeId.wrap(_nextId);
            _nextId += 1;
        }

        _systemTime = systemTime;
        _connection = new NodeConnection(host, port, binaryPacketFormat, threadPool);
        _initializationTime = _systemTime.getCurrentTimeInMilliSeconds();
        _threadPool = threadPool;

        final ReentrantReadWriteLock queueMessageLock = new ReentrantReadWriteLock();
        _sendSingleMessageLock = queueMessageLock.readLock();
        _sendMultiMessageLock = queueMessageLock.writeLock();

        _initConnection();
    }

    public Node(final BinarySocket binarySocket, final ThreadPool threadPool) {
        synchronized (NODE_ID_MUTEX) {
            _id = NodeId.wrap(_nextId);
            _nextId += 1;
        }

        _systemTime = new SystemTime();
        _connection = new NodeConnection(binarySocket, threadPool);
        _initializationTime = _systemTime.getCurrentTimeInMilliSeconds();
        _threadPool = threadPool;

        final ReentrantReadWriteLock queueMessageLock = new ReentrantReadWriteLock();
        _sendSingleMessageLock = queueMessageLock.readLock();
        _sendMultiMessageLock = queueMessageLock.writeLock();

        _initConnection();
    }

    public NodeId getId() { return _id; }

    public Long getInitializationTimestamp() {
        return _initializationTime;
    }

    public void handshake() {
        _handshake();
    }

    public Boolean handshakeIsComplete() {
        return _handshakeIsComplete;
    }

    public Long getNetworkTimeOffset() {
        return _networkTimeOffset;
    }

    public Long getLastMessageReceivedTimestamp() {
        return _lastMessageReceivedTimestamp;
    }

    public Boolean hasActiveConnection() {
        return ( (_connection.isConnected()) && (_lastMessageReceivedTimestamp > 0) );
    }

    public String getConnectionString() {
        final Ip ip = _connection.getIp();
        return ((ip != null ? ip.toString() : _connection.getHost()) + ":" + _connection.getPort());
    }

    public NodeIpAddress getRemoteNodeIpAddress() {
        final Ip ip;
        {
            final Ip connectionIp = _connection.getIp();
            ip = (connectionIp != null ? connectionIp : Ip.fromString(_connection.getHost()));
        }

        if (ip == null) {
            return null;
        }

        return new NodeIpAddress(ip, _connection.getPort());
    }

    public NodeIpAddress getLocalNodeIpAddress() {
        if (! _handshakeIsComplete) { return null; }
        if (_localNodeIpAddress == null) { return null; }

        return _localNodeIpAddress.copy();
    }

    public void setNodeAddressesReceivedCallback(final NodeAddressesReceivedCallback nodeAddressesReceivedCallback) {
        _nodeAddressesReceivedCallback = nodeAddressesReceivedCallback;
    }

    public void setNodeConnectedCallback(final NodeConnectedCallback nodeConnectedCallback) {
        _nodeConnectedCallback = nodeConnectedCallback;

        if (_connection.isConnected()) {
            if (nodeConnectedCallback != null) {
                nodeConnectedCallback.onNodeConnected();
            }
        }
    }

    public void setNodeHandshakeCompleteCallback(final NodeHandshakeCompleteCallback nodeHandshakeCompleteCallback) {
        _nodeHandshakeCompleteCallback = nodeHandshakeCompleteCallback;
    }

    public void setNodeDisconnectedCallback(final NodeDisconnectedCallback nodeDisconnectedCallback) {
        _nodeDisconnectedCallback = nodeDisconnectedCallback;
    }

    public void ping(final PingCallback pingCallback) {
        _ping(pingCallback);
    }

    public void broadcastNodeAddress(final NodeIpAddress nodeIpAddress) {
        final NodeIpAddressMessage nodeIpAddressMessage = _createNodeIpAddressMessage();
        nodeIpAddressMessage.addAddress(nodeIpAddress);
        _queueMessage(nodeIpAddressMessage);
    }

    public void broadcastNodeAddresses(final List<? extends NodeIpAddress> nodeIpAddresses) {
        final NodeIpAddressMessage nodeIpAddressMessage = _createNodeIpAddressMessage();
        for (final NodeIpAddress nodeIpAddress : nodeIpAddresses) {
            nodeIpAddressMessage.addAddress(nodeIpAddress);
        }
        _queueMessage(nodeIpAddressMessage);
    }

    /**
     * NodeConnection::connect must be called, even if the underlying socket was already connected.
     */
    public void connect() {
        _connection.connect();
    }

    public void disconnect() {
        _disconnect();
    }

    public Boolean isConnected() {
        return _connection.isConnected();
    }

    /**
     * Attempts to look up the Node's host, or returns null if the lookup fails.
     */
    public String getHost() {
        return _connection.getHost();
    }

    public Ip getIp() {
        return _connection.getIp();
    }

    public Integer getPort() {
        return _connection.getPort();
    }

    @Override
    public String toString() {
        return _connection.toString();
    }

    @Override
    public int hashCode() {
        return _id.hashCode();
    }

    @Override
    public boolean equals(final Object object) {
        if (! (object instanceof Node)) { return false; }
        return Util.areEqual(_id, ((Node) object)._id);
    }

    public Long getAveragePing() {
        final int itemCount = _latencies.getItemCount();
        long sum = 0L;
        long count = 0L;
        for (int i = 0; i < itemCount; ++i) {
            final Long value = _latencies.get(i);
            if (value == null) { continue; }

            sum += value;
            count += 1L;
        }
        if (count == 0L) { return Long.MAX_VALUE; }

        return (sum / count);
    }
}
