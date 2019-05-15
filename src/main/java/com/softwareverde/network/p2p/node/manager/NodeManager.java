package com.softwareverde.network.p2p.node.manager;

import com.softwareverde.async.ConcurrentHashSet;
import com.softwareverde.concurrent.pool.ThreadPool;
import com.softwareverde.constable.list.List;
import com.softwareverde.constable.list.immutable.ImmutableListBuilder;
import com.softwareverde.constable.list.mutable.MutableList;
import com.softwareverde.io.Logger;
import com.softwareverde.network.p2p.node.Node;
import com.softwareverde.network.p2p.node.NodeFactory;
import com.softwareverde.network.p2p.node.NodeId;
import com.softwareverde.network.p2p.node.address.NodeIpAddress;
import com.softwareverde.network.p2p.node.manager.health.MutableNodeHealth;
import com.softwareverde.network.p2p.node.manager.health.NodeHealth;
import com.softwareverde.network.time.MutableNetworkTime;
import com.softwareverde.network.time.NetworkTime;
import com.softwareverde.util.Container;
import com.softwareverde.util.Util;
import com.softwareverde.util.type.time.SystemTime;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NodeManager<NODE extends Node> {
    public static Boolean LOGGING_ENABLED = false;

    protected static ThreadPool _threadPool;

    public interface NodeFilter<NODE> {
        Boolean meetsCriteria(NODE node);
    }

    public interface NodeApiTransmission { }

    /**
     * A NodeJsonRpcConnection invocation that does not invoke a response.
     */
    public interface NodeApiMessage<NODE> extends NodeApiTransmission {
        void run(NODE node);
    }

    /**
     * NodeApiRequest.run() should invoke an Api Call on the provided Node.
     *  It is required that within the NodeApiRequestCallback, NodeApiRequestCallback::didTimeout is invoked immediately.
     *  NodeApiRequestCallback::didTimeout cancels the retry-thread timeout and returns true if the request has already timed out.
     *  If nodeApiInvocationCallback.didTimeout() returns true, then the the NodeApiRequestCallback should abort.
     */
    public static abstract class NodeApiRequest<NODE> implements NodeApiMessage<NODE> {
        private MutableNodeHealth.Request nodeHealthRequest;
        public Boolean didTimeout = false;
        public abstract void onFailure();
    }

    protected class NodeMaintenanceThread extends Thread {
        public NodeMaintenanceThread() {
            this.setName("Node Manager - Maintenance Thread - " + this.getId());
        }

        @Override
        public void run() {
            while (true) {
                _pingIdleNodes();
                _removeDisconnectedNodes();

                try { Thread.sleep(10000L); } catch (final Exception exception) { break; }
            }

            if (LOGGING_ENABLED) {
                Logger.log("Node Maintenance Thread exiting...");
            }
        }
    }

    // protected final Object _mutex = new Object();
    protected final SystemTime _systemTime;
    protected final NodeFactory<NODE> _nodeFactory;
    protected final ConcurrentHashMap<NodeId, NODE> _nodes;
    protected ConcurrentHashMap<NodeId, NODE> _pendingNodes = new ConcurrentHashMap<>(); // Nodes that have been added but have not yet completed their handshake...
    protected final ConcurrentHashMap<NodeId, MutableNodeHealth> _nodeHealthMap;
    protected final ConcurrentLinkedQueue<NodeApiMessage<NODE>> _queuedTransmissions = new ConcurrentLinkedQueue<>();
    protected final PendingRequestsManager<NODE> _pendingRequestsManager;
    protected final ConcurrentHashSet<NodeIpAddress> _nodeAddresses = new ConcurrentHashSet<>();
    protected final Thread _nodeMaintenanceThread = new NodeMaintenanceThread();
    protected final Integer _maxNodeCount;
    protected final MutableNetworkTime _networkTime;
    protected Boolean _isShuttingDown = false;

    protected ConcurrentHashSet<NodeIpAddress> _newNodeAddresses = new ConcurrentHashSet<>();
    protected Long _lastAddressBroadcastTimestamp = 0L;

    protected void _onAllNodesDisconnected() { }
    protected void _onNodeHandshakeComplete(final NODE node) { }
    protected void _onNodeConnected(final NODE node) { }

    protected void _addHandshakedNode(final NODE node) {
        final NodeId newNodeId = node.getId();
        _nodes.put(newNodeId, node);
        _nodeHealthMap.put(newNodeId, new MutableNodeHealth(newNodeId, _systemTime));
    }

    protected void _addNotHandshakedNode(final NODE node) {
        final Long nowInMilliseconds = _systemTime.getCurrentTimeInMilliSeconds();

        { // Cleanup any pending nodes that still haven't completed their handshake...
            final Map<NodeId, NODE> pendingNodes = _pendingNodes;
            _pendingNodes = new ConcurrentHashMap<>(pendingNodes);

            for (final NODE oldPendingNode : pendingNodes.values()) {
                final Long pendingSinceTimeMilliseconds = oldPendingNode.getInitializationTimestamp();
                if (nowInMilliseconds - pendingSinceTimeMilliseconds < 30000L) {
                    _pendingNodes.put(oldPendingNode.getId(), oldPendingNode);
                }
                else {
                    oldPendingNode.disconnect();
                }
            }
        }

        _pendingNodes.put(node.getId(), node);
    }

    protected void _removeNode(final NODE node) {
        final NodeId nodeId = node.getId();

        if (LOGGING_ENABLED) {
            final NodeHealth nodeHealth = _nodeHealthMap.get(nodeId);
            Logger.log("P2P: Dropped Node: " + node.getConnectionString() + " - " + (nodeHealth != null ? nodeHealth.getHealth() : "??? ") +  "hp");
        }

        _nodes.remove(nodeId);
        _pendingNodes.remove(nodeId);
        _nodeHealthMap.remove(nodeId);

        node.setNodeDisconnectedCallback(null);
        node.setNodeHandshakeCompleteCallback(null);
        node.setNodeConnectedCallback(null);
        node.setNodeAddressesReceivedCallback(null);

        node.disconnect();

        if (_nodes.isEmpty()) {
            if (! _isShuttingDown) {
                _onAllNodesDisconnected();
            }
        }
    }

    protected void _checkMaxNodeCount(final Integer maxNodeCount) {
        if (maxNodeCount > 0) {
            while (_nodes.size() > maxNodeCount) {
                final List<NODE> inactiveNodes = _getInactiveNodes();
                if (inactiveNodes.getSize() > 0) {
                    final NODE inactiveNode = inactiveNodes.get(0);
                    _removeNode(inactiveNode);
                    continue;
                }

                final NODE worstActiveNode = _selectWorstActiveNode();
                if (worstActiveNode != null) {
                    _removeNode(worstActiveNode);
                    continue;
                }

                final Set<NodeId> keySet = _nodes.keySet();
                final NodeId firstKey = keySet.iterator().next();
                final NODE node = _nodes.get(firstKey);
                _removeNode(node);
            }
        }
    }

    protected void _broadcastNewNodesToExistingNodes(final List<NodeIpAddress> nodeIpAddresses) {
        for (final NODE node : _nodes.values()) {
            node.broadcastNodeAddresses(nodeIpAddresses);

            if (LOGGING_ENABLED) {
                Logger.log("P2P: Broadcasting " + nodeIpAddresses.getSize() + " new Nodes to existing Node (" + node + ")");
            }
        }
    }

    protected void _broadcastExistingNodesToNewNode(final NODE newNode) {
        final Collection<NODE> nodes = _nodes.values();

        final MutableList<NodeIpAddress> nodeAddresses = new MutableList<>(nodes.size());
        for (final NODE node : nodes) {
            final NodeIpAddress nodeIpAddress = node.getRemoteNodeIpAddress();
            if (nodeIpAddress == null) { continue; }

            nodeAddresses.add(nodeIpAddress);

            if (LOGGING_ENABLED) {
                Logger.log("P2P: Broadcasting Existing Node (" + nodeIpAddress + ") to New Node (" + newNode + ")");
            }
        }

        newNode.broadcastNodeAddresses(nodeAddresses);
    }

    protected void _onNodeDisconnected(final NODE node) {
        if (LOGGING_ENABLED) {
            Logger.log("P2P: Node Disconnected: " + node.getConnectionString());
        }

        _removeNode(node);
    }

    protected void _processQueuedMessages() {
        // Copy the list of queued transactions since _selectNodeForRequest and _sendMessage could potentially requeue the transmission...
        final MutableList<NodeApiMessage<NODE>> queuedTransmissions = new MutableList<>(_queuedTransmissions.size());
        while (! _queuedTransmissions.isEmpty()) {
            final NodeApiMessage<NODE> message = _queuedTransmissions.poll();
            if (message != null) {
                queuedTransmissions.add(message);
            }
        }

        _threadPool.execute(new Runnable() {
            @Override
            public void run() {
                for (final NodeApiMessage<NODE> apiTransmission : queuedTransmissions) {
                    if (apiTransmission instanceof NodeApiRequest) {
                        _selectNodeForRequest((NodeApiRequest<NODE>) apiTransmission);
                    }
                    else {
                        _sendMessage(apiTransmission);
                    }
                }
            }
        });
    }

    protected Boolean _isConnectedToNode(final NodeIpAddress nodeIpAddress) {
        for (final NODE existingNode : _nodes.values()) {
            final NodeIpAddress existingNodeIpAddress = existingNode.getRemoteNodeIpAddress();

            final Boolean isAlreadyConnectedToNode = (Util.areEqual(nodeIpAddress, existingNodeIpAddress));
            if (isAlreadyConnectedToNode) { return true; }
        }

        return false;
    }

    protected void _initNode(final NODE node) {
        // final Container<Boolean> nodeConnected = new Container<Boolean>(null);

        final Container<Boolean> nodeDidConnect = new Container<>(null);

        final Runnable timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                try {
                    synchronized (nodeDidConnect) {
                        if (! Util.coalesce(nodeDidConnect.value, false)) {
                            nodeDidConnect.wait(10000L);
                        }
                    }

                    synchronized (nodeDidConnect) {
                        if (nodeDidConnect.value != null) { // Node connected successfully or has already been marked as disconnected...
                            return;
                        }

                        nodeDidConnect.value = false;

                        if (LOGGING_ENABLED) {
                            Logger.log("P2P: Node failed to connect. Purging node.");
                        }

                        _pendingNodes.remove(node.getId());

                        node.disconnect();

                        if (LOGGING_ENABLED) {
                            Logger.log("P2P: Node purged.");
                        }

                        if (_nodes.isEmpty()) {
                            if (!_isShuttingDown) {
                                _onAllNodesDisconnected();
                            }
                        }
                    }
                }
                catch (final Exception exception) { }
            }

            @Override
            public String toString() {
                return (NodeManager.this.getClass().getCanonicalName() + "." + "TimeoutRunnable"); // Used a hack for FakeThreadPools...
            }
        };

        node.setNodeAddressesReceivedCallback(new NODE.NodeAddressesReceivedCallback() {
            @Override
            public void onNewNodeAddresses(final List<NodeIpAddress> nodeIpAddresses) {
                if (_isShuttingDown) { return; }

                final List<NodeIpAddress> unseenNodeAddresses;
                {
                    final ImmutableListBuilder<NodeIpAddress> listBuilder = new ImmutableListBuilder<>(nodeIpAddresses.getSize());
                    for (final NodeIpAddress nodeIpAddress : nodeIpAddresses) {
                        final Boolean haveAlreadySeenNode = _nodeAddresses.contains(nodeIpAddress);
                        if (haveAlreadySeenNode) { continue; }

                        listBuilder.add(nodeIpAddress);
                        _nodeAddresses.add(nodeIpAddress);
                        _newNodeAddresses.add(nodeIpAddress);
                    }
                    unseenNodeAddresses = listBuilder.build();
                }
                if (unseenNodeAddresses.isEmpty()) { return; }

                if (_isShuttingDown) { return; }

                { // Batch at least 30 seconds worth of new NodeIpAddresses, then broadcast the group to current peers...
                    final Long now = _systemTime.getCurrentTimeInMilliSeconds();
                    final Long msElapsedSinceLastBroadcast = (now - _lastAddressBroadcastTimestamp);
                    if (msElapsedSinceLastBroadcast >= 30000L) {
                        _lastAddressBroadcastTimestamp = now;

                        final List<NodeIpAddress> newNodeAddresses = new MutableList<>(_newNodeAddresses);
                        _newNodeAddresses = new ConcurrentHashSet<>();
                        _broadcastNewNodesToExistingNodes(newNodeAddresses);
                    }
                }

                // Connect to the node if the node if the NodeManager is still looking for peers...
                for (final NodeIpAddress nodeIpAddress : unseenNodeAddresses) {
                    final Integer healthyNodeCount = _countNodesAboveHealth(50);
                    if (healthyNodeCount >= _maxNodeCount) { break; }

                    final String address = nodeIpAddress.getIp().toString();
                    final Integer port = nodeIpAddress.getPort();

                    final Boolean isAlreadyConnectedToNode = _isConnectedToNode(nodeIpAddress);
                    if (isAlreadyConnectedToNode) { continue; }

                    final NODE newNode = _nodeFactory.newNode(address, port);

                    _initNode(newNode);

                    _broadcastExistingNodesToNewNode(newNode);

                    _checkMaxNodeCount(_maxNodeCount - 1);

                    _addNotHandshakedNode(newNode);
                }
            }
        });

        node.setNodeConnectedCallback(new NODE.NodeConnectedCallback() {
            @Override
            public void onNodeConnected() {
                { // Handle connection timeout...
                    synchronized (nodeDidConnect) {
                        if (nodeDidConnect.value == null) {
                            nodeDidConnect.value = true;
                        }
                        else if (! nodeDidConnect.value) {
                            // Node connection timed out; abort.
                            return;
                        }

                        nodeDidConnect.notifyAll();
                    }
                }

                _onNodeConnected(node);
                _processQueuedMessages();
            }
        });

        node.setNodeHandshakeCompleteCallback(new NODE.NodeHandshakeCompleteCallback() {
            @Override
            public void onHandshakeComplete() {
                if (LOGGING_ENABLED) {
                    Logger.log("P2P: HandshakeComplete: " + node.getConnectionString());
                }

                _pendingNodes.remove(node.getId());
                _addHandshakedNode(node);

                final Long nodeNetworkTimeOffset = node.getNetworkTimeOffset();
                if (nodeNetworkTimeOffset != null) {
                    _networkTime.includeOffsetInSeconds(nodeNetworkTimeOffset);
                }

                _onNodeHandshakeComplete(node);
                _processQueuedMessages();
            }
        });

        node.setNodeDisconnectedCallback(new NODE.NodeDisconnectedCallback() {
            @Override
            public void onNodeDisconnected() {
                _onNodeDisconnected(node);
            }
        });

        node.connect();
        node.handshake();

        _threadPool.execute(timeoutRunnable);
    }

    protected Integer _countNodesAboveHealth(final Integer minimumHealth) {
        int nodeCount = 0;
        final List<NODE> activeNodes = _getActiveNodes();
        for (final NODE node : activeNodes) {
            final MutableNodeHealth nodeHealth = _nodeHealthMap.get(node.getId());
            if (nodeHealth.getHealth() > minimumHealth) {
                nodeCount += 1;
            }
        }
        return nodeCount;
    }

    protected List<NODE> _getInactiveNodes() {
        final MutableList<NODE> inactiveNodes = new MutableList<>(_nodes.size());
        for (final NODE node : _nodes.values()) {
            if (! node.hasActiveConnection()) {
                inactiveNodes.add(node);
            }
        }
        return inactiveNodes;
    }

    protected List<NODE> _getActiveNodes() {
        final MutableList<NODE> activeNodes = new MutableList<>(_nodes.size());
        for (final NODE node : _nodes.values()) {
            if (node.hasActiveConnection()) {
                activeNodes.add(node);
            }
        }

        return activeNodes;
    }

    protected NODE _selectWorstActiveNode() {
        final List<NODE> activeNodes = _getActiveNodes();

        final Integer activeNodeCount = activeNodes.getSize();
        if (activeNodeCount == 0) { return null; }

        final MutableList<NodeHealth> nodeHealthList = new MutableList<>(activeNodeCount);
        for (final NODE activeNode : activeNodes) {
            final MutableNodeHealth nodeHealth = _nodeHealthMap.get(activeNode.getId());
            if (nodeHealth != null) {
                nodeHealthList.add(nodeHealth.asConst());
            }
        }
        nodeHealthList.sort(MutableNodeHealth.HEALTH_ASCENDING_COMPARATOR);

        for (int i = 0; i < nodeHealthList.getSize(); ++i) {
            final NodeHealth worstNodeHealth = nodeHealthList.get(i);
            final NODE worstNode = _nodes.get(worstNodeHealth.getNodeId());
            if (worstNode != null) { // _nodes may have been updated while the health was sorted, so it is possible that the worst node is no longer connected...
                return worstNode;
            }
        }

        return null;
    }

    protected NODE _selectBestNode() {
        final List<NODE> nodes = _selectBestNodes(1);
        if ( (nodes == null) || (nodes.isEmpty()) ) { return null; }

        final NODE selectedNode = nodes.get(0);

        if (LOGGING_ENABLED) {
            final NodeHealth nodeHealth = _nodeHealthMap.get(selectedNode.getId());
            Logger.log("P2P: Selected Node: " + (selectedNode.getId()) + " (" + (nodeHealth != null ? nodeHealth.getHealth() : "??? ") + "hp) - " + (selectedNode.getConnectionString()) + " - " + _nodes.size());
        }

        return selectedNode;
    }

    protected NODE _selectBestNode(final NodeFilter<NODE> nodeFilter) {
        final List<NODE> nodes = _selectBestNodes(_maxNodeCount);
        if ( (nodes == null) || (nodes.isEmpty()) ) { return null; }

        for (final NODE node : nodes) {
            if (! nodeFilter.meetsCriteria(node)) { continue; }

            if (LOGGING_ENABLED) {
                final NodeHealth nodeHealth = _nodeHealthMap.get(node.getId());
                if (nodeHealth != null) {
                    Logger.log("P2P: Selected Node: " + (node.getId()) + " (" + (nodeHealth != null ? nodeHealth.getHealth() : "??? ") + "hp) - " + (node.getConnectionString()) + " - " + _nodes.size());
                }
            }

            return node;
        }

        return null;
    }

    protected List<NODE> _selectBestNodes(final Integer requestedNodeCount) {
        final List<NODE> activeNodes = _getActiveNodes();

        final Integer activeNodeCount = activeNodes.getSize();
        if (activeNodeCount == 0) { return null; }

        final MutableList<NodeHealth> nodeHealthList = new MutableList<>(activeNodeCount);
        for (final NODE activeNode : activeNodes) {
            final NodeHealth nodeHealth = _nodeHealthMap.get(activeNode.getId());
            if (nodeHealth != null) {
                nodeHealthList.add(nodeHealth.asConst()); // NOTE: Items must be a snapshot to prevent concurrent modifications during sort...
            }
        }
        nodeHealthList.sort(NodeHealth.HEALTH_ASCENDING_COMPARATOR);

        final Integer nodeCount;
        {
            if ( (requestedNodeCount >= _nodes.size()) || (requestedNodeCount < 0) ) {
                nodeCount = _nodes.size();
            }
            else {
                nodeCount = requestedNodeCount;
            }
        }

        final MutableList<NODE> selectedNodes = new MutableList<>(nodeCount);
        for (int i = 0; i < nodeCount; ++i) {
            final int index = (nodeHealthList.getSize() - i - 1);
            if ( (index < 0) || (index >= nodeHealthList.getSize()) ) { continue; }

            final NodeHealth bestNodeHealth = nodeHealthList.get(index);
            final NODE selectedNode = _nodes.get(bestNodeHealth.getNodeId());
            if (selectedNode != null) { // _nodes may have been updated during the selection process...
                selectedNodes.add(selectedNode);
            }
        }

        return selectedNodes;
    }

    protected void _pingIdleNodes() {
        final Long maxIdleTime = 30000L;

        final Long now = _systemTime.getCurrentTimeInMilliSeconds();

        final MutableList<NODE> idleNodes = new MutableList<>(_nodes.size());
        for (final NODE node : _nodes.values()) {
            final Long lastMessageTime = node.getLastMessageReceivedTimestamp();
            final Long idleDuration = (now - lastMessageTime); // NOTE: Race conditions could result in a negative value...

            if (idleDuration > maxIdleTime) {
                idleNodes.add(node);
            }
        }

        if (LOGGING_ENABLED) {
            Logger.log("P2P: Idle Node Count: " + idleNodes.getSize() + " / " + _nodes.size());
        }

        for (final NODE idleNode : idleNodes) {
            // final NodeId nodeId = idleNode.getId();
            // _nodeHealthMap.get(nodeId).onRequestSent();

            if (! idleNode.handshakeIsComplete()) { return; }

            if (LOGGING_ENABLED) {
                Logger.log("P2P: Pinging Idle Node: " + idleNode.getConnectionString());
            }

            idleNode.ping(new NODE.PingCallback() {
                @Override
                public void onResult(final Long pingInMilliseconds) {
                    if (LOGGING_ENABLED) {
                        Logger.log("P2P: Node Pong: " + pingInMilliseconds);
                    }

                    final NodeId nodeId = idleNode.getId();
                    final MutableNodeHealth nodeHealth = _nodeHealthMap.get(nodeId);
                    if (nodeHealth != null) {
                        nodeHealth.updatePingInMilliseconds(pingInMilliseconds);
                    }
                }
            });
        }
    }

    protected void _removeDisconnectedNodes() {
        final MutableList<NODE> purgeableNodes = new MutableList<>();

        for (final NODE node : _nodes.values()) {
            if (! node.isConnected()) {
                final Long nodeAge = (_systemTime.getCurrentTimeInMilliSeconds() - node.getInitializationTimestamp());
                if (nodeAge > 10000L) {
                    purgeableNodes.add(node);
                }
            }
        }

        for (final NODE node : purgeableNodes) {
            _removeNode(node);
        }
    }

    public NodeManager(final Integer maxNodeCount, final NodeFactory<NODE> nodeFactory, final MutableNetworkTime networkTime, final ThreadPool threadPool) {
        _systemTime = new SystemTime();
        _nodes = new ConcurrentHashMap<>(maxNodeCount);
        _nodeHealthMap = new ConcurrentHashMap<>(maxNodeCount);

        _maxNodeCount = maxNodeCount;
        _nodeFactory = nodeFactory;
        _networkTime = networkTime;
        _pendingRequestsManager = new PendingRequestsManager<>(_systemTime, threadPool);
        _threadPool = threadPool;
    }

    public NodeManager(final Integer maxNodeCount, final NodeFactory<NODE> nodeFactory, final MutableNetworkTime networkTime, final SystemTime systemTime, final ThreadPool threadPool) {
        _nodes = new ConcurrentHashMap<>(maxNodeCount);
        _nodeHealthMap = new ConcurrentHashMap<>(maxNodeCount);

        _maxNodeCount = maxNodeCount;
        _nodeFactory = nodeFactory;
        _networkTime = networkTime;
        _systemTime = systemTime;
        _pendingRequestsManager = new PendingRequestsManager<>(_systemTime, threadPool);
        _threadPool = threadPool;
    }

    public void addNode(final NODE node) {
        if (_isShuttingDown) { return; }

        _initNode(node);

        _checkMaxNodeCount(_maxNodeCount - 1);
        _addNotHandshakedNode(node);
    }

    public NetworkTime getNetworkTime() {
        return _networkTime;
    }

    public void startNodeMaintenanceThread() {
        _nodeMaintenanceThread.start();
    }

    public void stopNodeMaintenanceThread() {
        _nodeMaintenanceThread.interrupt();
        try { _nodeMaintenanceThread.join(10000L); } catch (final Exception exception) { }
    }

    protected void _selectNodeForRequest(final NodeApiRequest<NODE> apiRequest) {
        final NODE selectedNode;
        final MutableNodeHealth nodeHealth;
        {
            selectedNode = _selectBestNode();

            if (selectedNode == null) {
                _queuedTransmissions.add(apiRequest);
                return;
            }

            final NodeId nodeId = selectedNode.getId();
            nodeHealth = _nodeHealthMap.get(nodeId);
        }

        apiRequest.nodeHealthRequest = nodeHealth.onRequestSent();
        _pendingRequestsManager.addPendingRequest(apiRequest);
        apiRequest.run(selectedNode);

        _pendingRequestsManager.wakeUp();
    }

    protected void _selectNodeForRequest(final NODE selectedNode, final NodeApiRequest<NODE> apiRequest) {
        final MutableNodeHealth nodeHealth;
        {
            if (selectedNode == null) {
                _queuedTransmissions.add(apiRequest);
                return;
            }

            final NodeId nodeId = selectedNode.getId();
            nodeHealth = _nodeHealthMap.get(nodeId);
        }

        if (nodeHealth == null) {
            Logger.log("Selected node no longer connected: " + selectedNode.getConnectionString());
            apiRequest.onFailure();
            return;
        }

        apiRequest.nodeHealthRequest = nodeHealth.onRequestSent();
        _pendingRequestsManager.addPendingRequest(apiRequest);
        apiRequest.run(selectedNode);

        _pendingRequestsManager.wakeUp();
    }

    protected void _onResponseReceived(final NODE selectedNode, final NodeApiRequest<NODE> apiRequest) {
        _pendingRequestsManager.removePendingRequest(apiRequest);
        final NodeId nodeId = selectedNode.getId();
        final MutableNodeHealth nodeHealth = _nodeHealthMap.get(nodeId);
        if (nodeHealth != null) {
            nodeHealth.onResponseReceived(apiRequest.nodeHealthRequest);
        }
    }

    protected void _sendMessage(final NodeApiMessage<NODE> apiMessage) {
        final NODE selectedNode = _selectBestNode();

        if (selectedNode == null) {
            _queuedTransmissions.add(apiMessage);
            return;
        }

        final NodeId nodeId = selectedNode.getId();
        final MutableNodeHealth nodeHealth = _nodeHealthMap.get(nodeId);
        if (nodeHealth != null) {
            nodeHealth.onMessageSent();
        }

        apiMessage.run(selectedNode);
    }

    public void executeRequest(final NodeApiRequest<NODE> nodeNodeApiRequest) {
        _selectNodeForRequest(nodeNodeApiRequest);
    }

    public void sendMessage(final NodeApiMessage<NODE> nodeNodeApiMessage) {
        _sendMessage(nodeNodeApiMessage);
    }

    public List<NODE> getNodes() {
        return new MutableList<>(_nodes.values());
    }

    public List<NodeId> getNodeIds() {
        final ImmutableListBuilder<NodeId> nodeIds = new ImmutableListBuilder<>(_nodes.size());
        nodeIds.addAll(_nodes.keySet());
        return nodeIds.build();
    }

    public NODE getNode(final NodeId nodeId) {
        return _nodes.get(nodeId);
    }

    public NODE getNode(final NodeFilter<NODE> nodeFilter) {
        return _selectBestNode(nodeFilter);
    }

    public Long getNodeHealth(final NodeId nodeId) {
        final MutableNodeHealth nodeHealth = _nodeHealthMap.get(nodeId);
        if (nodeHealth == null) { return null; }

        return nodeHealth.getHealth();
    }

    public NODE getBestNode() {
        return _selectBestNode();
    }

    public List<NODE> getBestNodes(final Integer nodeCount) {
        return _selectBestNodes(nodeCount);
    }

    public NODE getWorstNode() {
        return _selectWorstActiveNode();
    }

    public Integer getActiveNodeCount() {
        final List<NODE> nodes = _getActiveNodes();
        return nodes.getSize();
    }

    public void shutdown() {
        _isShuttingDown = true;
        final MutableList<NODE> nodes = new MutableList<>(_nodes.values());
        nodes.addAll(_pendingNodes.values());
        _nodes.clear();
        _pendingNodes.clear();

        // Nodes must be disconnected outside of the _mutex lock in order to prevent deadlock...
        for (final NODE node : nodes) {
            node.disconnect();
        }

        _pendingRequestsManager.stop();
    }
}
