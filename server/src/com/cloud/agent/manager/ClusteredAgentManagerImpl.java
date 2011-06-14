/**
 * Copyright (c) 2008, 2009, VMOps Inc.
 *
 * This code is Copyrighted and must not be reused, modified, or redistributed without the explicit consent of VMOps.
 */
package com.cloud.agent.manager;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.ejb.Local;
import javax.naming.ConfigurationException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import org.apache.log4j.Logger;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CancelCommand;
import com.cloud.agent.api.ChangeAgentCommand;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.TransferAgentCommand;
import com.cloud.agent.transport.Request;
import com.cloud.agent.transport.Request.Version;
import com.cloud.agent.transport.Response;
import com.cloud.api.commands.UpdateHostPasswordCmd;
import com.cloud.cluster.ClusterManager;
import com.cloud.cluster.ClusterManagerListener;
import com.cloud.cluster.ClusteredAgentRebalanceService;
import com.cloud.cluster.ManagementServerHost;
import com.cloud.cluster.ManagementServerHostVO;
import com.cloud.cluster.agentlb.AgentLoadBalancerPlanner;
import com.cloud.cluster.agentlb.HostTransferMapVO;
import com.cloud.cluster.agentlb.HostTransferMapVO.HostTransferState;
import com.cloud.cluster.agentlb.dao.HostTransferMapDao;
import com.cloud.cluster.dao.ManagementServerHostDao;
import com.cloud.configuration.Config;
import com.cloud.configuration.dao.ConfigurationDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.Status.Event;
import com.cloud.resource.ServerResource;
import com.cloud.storage.resource.DummySecondaryStorageResource;
import com.cloud.user.User;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.component.Adapters;
import com.cloud.utils.component.ComponentLocator;
import com.cloud.utils.component.Inject;
import com.cloud.utils.concurrency.NamedThreadFactory;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.nio.Link;
import com.cloud.utils.nio.Task;

@Local(value = { AgentManager.class, ClusteredAgentRebalanceService.class })
public class ClusteredAgentManagerImpl extends AgentManagerImpl implements ClusterManagerListener, ClusteredAgentRebalanceService {
    final static Logger s_logger = Logger.getLogger(ClusteredAgentManagerImpl.class);
    private static final ScheduledExecutorService s_transferExecutor = Executors.newScheduledThreadPool(1, new NamedThreadFactory("Cluster-AgentTransferExecutor"));

    public final static long STARTUP_DELAY = 5000;
    public final static long SCAN_INTERVAL = 90000; // 90 seconds, it takes 60 sec for xenserver to fail login
    public final static int ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION = 5; // 5 seconds
    public long _loadSize = 100;
    protected Set<Long> _agentToTransferIds = new HashSet<Long>();

    private final long rebalanceTimeOut = 300000; // 5 mins - after this time remove the agent from the transfer list 

    @Inject
    protected ClusterManager _clusterMgr = null;

    protected HashMap<String, SocketChannel> _peers;
    protected HashMap<String, SSLEngine> _sslEngines;
    private final Timer _timer = new Timer("ClusteredAgentManager Timer");

    @Inject
    protected ManagementServerHostDao _mshostDao;
    @Inject
    protected HostTransferMapDao _hostTransferDao;
    
    @Inject(adapter = AgentLoadBalancerPlanner.class)
    protected Adapters<AgentLoadBalancerPlanner> _lbPlanners;

    protected ClusteredAgentManagerImpl() {
        super();
    }

    @Override
    public boolean configure(String name, Map<String, Object> xmlParams) throws ConfigurationException {
        _peers = new HashMap<String, SocketChannel>(7);
        _sslEngines = new HashMap<String, SSLEngine>(7);
        _nodeId = _clusterMgr.getManagementNodeId();

        ConfigurationDao configDao = ComponentLocator.getCurrentLocator().getDao(ConfigurationDao.class);
        Map<String, String> params = configDao.getConfiguration(xmlParams);
        String value = params.get(Config.DirectAgentLoadSize.key());
        _loadSize = NumbersUtil.parseInt(value, 16);

        ClusteredAgentAttache.initialize(this);

        _clusterMgr.registerListener(this);

        return super.configure(name, xmlParams);
    }

    @Override
    public boolean start() {
        if (!super.start()) {
            return false;
        }
        _timer.schedule(new DirectAgentScanTimerTask(), STARTUP_DELAY, SCAN_INTERVAL);

        // schedule transfer scan executor - if agent LB is enabled
        if (_clusterMgr.isAgentRebalanceEnabled()) {
            s_transferExecutor.scheduleAtFixedRate(getTransferScanTask(), 60000, ClusteredAgentRebalanceService.DEFAULT_TRANSFER_CHECK_INTERVAL,
                    TimeUnit.MILLISECONDS);
        }

        return true;
    }

    private void runDirectAgentScanTimerTask() {
        GlobalLock scanLock = GlobalLock.getInternLock("clustermgr.scan");
        try {
            if (scanLock.lock(ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)) {
                try {
                    scanDirectAgentToLoad();
                } finally {
                    scanLock.unlock();
                }
            }
        } finally {
            scanLock.releaseRef();
        }
    }

    private void scanDirectAgentToLoad() {
        if (s_logger.isTraceEnabled()) {
            s_logger.trace("Begin scanning directly connected hosts");
        }

        // for agents that are self-managed, threshold to be considered as disconnected is 3 ping intervals
        long cutSeconds = (System.currentTimeMillis() >> 10) - (_pingInterval * 3);
        List<HostVO> hosts = _hostDao.findDirectAgentToLoad(cutSeconds, _loadSize);
        if (hosts != null && hosts.size() == _loadSize) {
            Long clusterId = hosts.get((int) (_loadSize - 1)).getClusterId();
            if (clusterId != null) {
                for (int i = (int) (_loadSize - 1); i > 0; i--) {
                    if (hosts.get(i).getClusterId() == clusterId) {
                        hosts.remove(i);
                    } else {
                        break;
                    }
                }
            }
        }
        if (hosts != null && hosts.size() > 0) {
            for (HostVO host : hosts) {
                try {
                    AgentAttache agentattache = findAttache(host.getId());
                    if (agentattache != null) {
                        // already loaded, skip
                        if (agentattache.forForward()) {
                            if (s_logger.isInfoEnabled()) {
                                s_logger.info(host + " is detected down, but we have a forward attache running, disconnect this one before launching the host");
                            }
                            removeAgent(agentattache, Status.Disconnected);
                        } else {
                            continue;
                        }
                    }

                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Loading directly connected host " + host.getId() + "(" + host.getName() + ")");
                    }
                    loadDirectlyConnectedHost(host, false);
                } catch (Throwable e) {
                    s_logger.debug(" can not load directly connected host " + host.getId() + "(" + host.getName() + ") due to " + e.toString());
                }
            }
        }

        if (s_logger.isTraceEnabled()) {
            s_logger.trace("End scanning directly connected hosts");
        }
    }

    private class DirectAgentScanTimerTask extends TimerTask {
        @Override
        public void run() {
            try {
                runDirectAgentScanTimerTask();
            } catch (Throwable e) {
                s_logger.error("Unexpected exception " + e.getMessage(), e);
            }
        }
    }

    @Override
    public Task create(Task.Type type, Link link, byte[] data) {
        return new ClusteredAgentHandler(type, link, data);
    }

    @Override
    public boolean cancelMaintenance(final long hostId) {
        try {
            Boolean result = _clusterMgr.propagateAgentEvent(hostId, Event.ResetRequested);

            if (result != null) {
                return result;
            }
        } catch (AgentUnavailableException e) {
            return false;
        }

        return super.cancelMaintenance(hostId);
    }

    protected AgentAttache createAttache(long id) {
        s_logger.debug("create forwarding ClusteredAgentAttache for " + id);
        final AgentAttache attache = new ClusteredAgentAttache(this, id);
        AgentAttache old = null;
        synchronized (_agents) {
            old = _agents.get(id);
            _agents.put(id, attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }
        return attache;
    }

    @Override
    protected AgentAttache createAttache(long id, HostVO server, Link link) {
        s_logger.debug("create ClusteredAgentAttache for " + id);
        final AgentAttache attache = new ClusteredAgentAttache(this, id, link, server.getStatus() == Status.Maintenance || server.getStatus() == Status.ErrorInMaintenance
                || server.getStatus() == Status.PrepareForMaintenance);
        link.attach(attache);
        AgentAttache old = null;
        synchronized (_agents) {
            old = _agents.get(id);
            _agents.put(id, attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }
        return attache;
    }

    @Override
    protected AgentAttache createAttache(long id, HostVO server, ServerResource resource) {
        if (resource instanceof DummySecondaryStorageResource) {
            return new DummyAttache(this, id, false);
        }
        s_logger.debug("create ClusteredDirectAgentAttache for " + id);
        final DirectAgentAttache attache = new ClusteredDirectAgentAttache(this, id, _nodeId, resource, server.getStatus() == Status.Maintenance || server.getStatus() == Status.ErrorInMaintenance
                || server.getStatus() == Status.PrepareForMaintenance, this);
        AgentAttache old = null;
        synchronized (_agents) {
            old = _agents.get(id);
            _agents.put(id, attache);
        }
        if (old != null) {
            old.disconnect(Status.Removed);
        }
        return attache;
    }

    @Override
    protected boolean handleDisconnect(AgentAttache attache, Status.Event event, boolean investigate) {
        return handleDisconnect(attache, event, investigate, true);
    }

    protected boolean handleDisconnect(AgentAttache agent, Status.Event event, boolean investigate, boolean broadcast) {
        if (agent == null) {
            return true;
        }

        if (super.handleDisconnect(agent, event, investigate)) {
            if (broadcast) {
                notifyNodesInCluster(agent);
            }
            return true;
        } else {
            return false;
        }
    }

    @Override
    public boolean executeUserRequest(long hostId, Event event) throws AgentUnavailableException {
        if (event == Event.AgentDisconnected) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Received agent disconnect event for host " + hostId);
            }
            AgentAttache attache = findAttache(hostId);
            if (attache != null) {
                handleDisconnect(attache, Event.AgentDisconnected, false, false);
            }

            return true;
        } else {
            return super.executeUserRequest(hostId, event);
        }
    }

    @Override
    public boolean maintain(final long hostId) throws AgentUnavailableException {
        Boolean result = _clusterMgr.propagateAgentEvent(hostId, Event.MaintenanceRequested);
        if (result != null) {
            return result;
        }

        return super.maintain(hostId);
    }

    @Override
    public boolean reconnect(final long hostId) throws AgentUnavailableException {
        Boolean result = _clusterMgr.propagateAgentEvent(hostId, Event.ShutdownRequested);
        if (result != null) {
            return result;
        }

        return super.reconnect(hostId);
    }

    @Override
    @DB
    public boolean deleteHost(long hostId, boolean isForced, User caller) {
        try {
            Boolean result = _clusterMgr.propagateAgentEvent(hostId, Event.Remove);
            if (result != null) {
                return result;
            }
        } catch (AgentUnavailableException e) {
            return false;
        }

        return super.deleteHost(hostId, isForced, caller);
    }

    @Override
    public boolean updateHostPassword(UpdateHostPasswordCmd upasscmd) {
        if (upasscmd.getClusterId() == null) {
            // update agent attache password
            try {
                Boolean result = _clusterMgr.propagateAgentEvent(upasscmd.getHostId(), Event.UpdatePassword);
                if (result != null) {
                    return result;
                }
            } catch (AgentUnavailableException e) {
            }
        } else {
            // get agents for the cluster
            List<HostVO> hosts = _hostDao.listByCluster(upasscmd.getClusterId());
            for (HostVO h : hosts) {
                try {
                    Boolean result = _clusterMgr.propagateAgentEvent(h.getId(), Event.UpdatePassword);
                    if (result != null) {
                        return result;
                    }
                } catch (AgentUnavailableException e) {
                }
            }
        }
        return super.updateHostPassword(upasscmd);
    }

    public void notifyNodesInCluster(AgentAttache attache) {
        s_logger.debug("Notifying other nodes of to disconnect");
        Command[] cmds = new Command[] { new ChangeAgentCommand(attache.getId(), Event.AgentDisconnected) };
        _clusterMgr.broadcast(attache.getId(), cmds);
    }

    protected static void logT(byte[] bytes, final String msg) {
        s_logger.trace("Seq " + Request.getAgentId(bytes) + "-" + Request.getSequence(bytes) + ": MgmtId " + Request.getManagementServerId(bytes) + ": "
                + (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    protected static void logD(byte[] bytes, final String msg) {
        s_logger.debug("Seq " + Request.getAgentId(bytes) + "-" + Request.getSequence(bytes) + ": MgmtId " + Request.getManagementServerId(bytes) + ": "
                + (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    protected static void logI(byte[] bytes, final String msg) {
        s_logger.info("Seq " + Request.getAgentId(bytes) + "-" + Request.getSequence(bytes) + ": MgmtId " + Request.getManagementServerId(bytes) + ": "
                + (Request.isRequest(bytes) ? "Req: " : "Resp: ") + msg);
    }

    public boolean routeToPeer(String peer, byte[] bytes) {
        int i = 0;
        SocketChannel ch = null;
        SSLEngine sslEngine = null;
        while (i++ < 5) {
            ch = connectToPeer(peer, ch);
            if (ch == null) {
                try {
                    logD(bytes, "Unable to route to peer: " + Request.parse(bytes).toString());
                } catch (Exception e) {
                }
                return false;
            }
            sslEngine = getSSLEngine(peer);
            if (sslEngine == null) {
                logD(bytes, "Unable to get SSLEngine of peer: " + peer);
                return false;
            }
            try {
                if (s_logger.isDebugEnabled()) {
                    logD(bytes, "Routing to peer");
                }
                Link.write(ch, new ByteBuffer[] { ByteBuffer.wrap(bytes) }, sslEngine);
                return true;
            } catch (IOException e) {
                try {
                    logI(bytes, "Unable to route to peer: " + Request.parse(bytes).toString() + " due to " + e.getMessage());
                } catch (Exception ex) {
                }
            }
        }
        return false;
    }

    public String findPeer(long hostId) {
        return _clusterMgr.getPeerName(hostId);
    }
    
    public SSLEngine getSSLEngine(String peerName) {
        return _sslEngines.get(peerName);
    }

    public void cancel(String peerName, long hostId, long sequence, String reason) {
        CancelCommand cancel = new CancelCommand(sequence, reason);
        Request req = new Request(hostId, _nodeId, cancel, true);
        req.setControl(true);
        routeToPeer(peerName, req.getBytes());
    }

    public void closePeer(String peerName) {
        synchronized (_peers) {
            SocketChannel ch = _peers.get(peerName);
            if (ch != null) {
                try {
                    ch.close();
                } catch (IOException e) {
                    s_logger.warn("Unable to close peer socket connection to " + peerName);
                }
            }
            _peers.remove(peerName);
            _sslEngines.remove(peerName);
        }
    }

    public SocketChannel connectToPeer(String peerName, SocketChannel prevCh) {
        synchronized (_peers) {
            SocketChannel ch = _peers.get(peerName);
            SSLEngine sslEngine = null;
            if (prevCh != null) {
                try {
                    prevCh.close();
                } catch (Exception e) {
                }
            }
            if (ch == null || ch == prevCh) {
                ManagementServerHostVO ms = _clusterMgr.getPeer(peerName);
                if (ms == null) {
                    s_logger.info("Unable to find peer: " + peerName);
                    return null;
                }
                String ip = ms.getServiceIP();
                InetAddress addr;
                try {
                    addr = InetAddress.getByName(ip);
                } catch (UnknownHostException e) {
                    throw new CloudRuntimeException("Unable to resolve " + ip);
                }
                try {
                    ch = SocketChannel.open(new InetSocketAddress(addr, _port));
                    ch.configureBlocking(true); // make sure we are working at blocking mode
                    ch.socket().setKeepAlive(true);
                    ch.socket().setSoTimeout(60 * 1000);
                    try {
                        SSLContext sslContext = Link.initSSLContext(true);
                        sslEngine = sslContext.createSSLEngine(ip, _port);
                        sslEngine.setUseClientMode(true);

                        Link.doHandshake(ch, sslEngine, true);
                        s_logger.info("SSL: Handshake done");
                    } catch (Exception e) {
                        throw new IOException("SSL: Fail to init SSL! " + e);
                    }
                    if (s_logger.isDebugEnabled()) {
                        s_logger.debug("Connection to peer opened: " + peerName + ", ip: " + ip);
                    }
                    _peers.put(peerName, ch);
                    _sslEngines.put(peerName, sslEngine);
                } catch (IOException e) {
                    s_logger.warn("Unable to connect to peer management server: " + peerName + ", ip: " + ip + " due to " + e.getMessage(), e);
                    return null;
                }
            }

            if (s_logger.isTraceEnabled()) {
                s_logger.trace("Found open channel for peer: " + peerName);
            }
            return ch;
        }
    }

    public SocketChannel connectToPeer(long hostId, SocketChannel prevCh) {
        String peerName = _clusterMgr.getPeerName(hostId);
        if (peerName == null) {
            return null;
        }

        return connectToPeer(peerName, prevCh);
    }

    @Override
    protected AgentAttache getAttache(final Long hostId) throws AgentUnavailableException {
        assert (hostId != null) : "Who didn't check their id value?";
        HostVO host = _hostDao.findById(hostId);
        if (host == null) {
            throw new AgentUnavailableException("Can't find the host ", hostId);
        }

        AgentAttache agent = findAttache(hostId);
        if (agent == null) {
            if (host.getStatus() == Status.Up && (host.getManagementServerId() != null && host.getManagementServerId() != _nodeId)) {
                agent = createAttache(hostId);
            }
        }
        if (agent == null) {
            throw new AgentUnavailableException("Host is not in the right state: " + host.getStatus() , hostId);
        }

        return agent;
    }

    @Override
    public boolean stop() {
        if (_peers != null) {
            for (SocketChannel ch : _peers.values()) {
                try {
                    s_logger.info("Closing: " + ch.toString());
                    ch.close();
                } catch (IOException e) {
                }
            }
        }
        _timer.cancel();
        
        //cancel all transfer tasks
        s_transferExecutor.shutdownNow();
        cleanupTransferMap();
        
        return super.stop();
    }

    @Override
    public void startDirectlyConnectedHosts() {
        // override and let it be dummy for purpose, we will scan and load direct agents periodically.
        // We may also pickup agents that have been left over from other crashed management server
    }

    public class ClusteredAgentHandler extends AgentHandler {

        public ClusteredAgentHandler(Task.Type type, Link link, byte[] data) {
            super(type, link, data);
        }

        @Override
        protected void doTask(final Task task) throws Exception {
            Transaction txn = Transaction.open(Transaction.CLOUD_DB);
            try {
                if (task.getType() != Task.Type.DATA) {
                    super.doTask(task);
                    return;
                }

                final byte[] data = task.getData();
                Version ver = Request.getVersion(data);
                if (ver.ordinal() != Version.v1.ordinal() && ver.ordinal() != Version.v3.ordinal()) {
                    s_logger.warn("Wrong version for clustered agent request");
                    super.doTask(task);
                    return;
                }

                long hostId = Request.getAgentId(data);
                Link link = task.getLink();

                if (Request.fromServer(data)) {

                    AgentAttache agent = findAttache(hostId);

                    if (Request.isControl(data)) {
                        if (agent == null) {
                            logD(data, "No attache to process cancellation");
                            return;
                        }
                        Request req = Request.parse(data);
                        Command[] cmds = req.getCommands();
                        CancelCommand cancel = (CancelCommand) cmds[0];
                        if (s_logger.isDebugEnabled()) {
                            logD(data, "Cancel request received");
                        }
                        agent.cancel(cancel.getSequence());
                        return;
                    }

                    try {
                        if (agent == null || agent.isClosed()) {
                            throw new AgentUnavailableException("Unable to route to agent ", hostId);
                        }

                        if (Request.isRequest(data) && Request.requiresSequentialExecution(data)) {
                            // route it to the agent.
                            // But we have the serialize the control commands here so we have
                            // to deserialize this and send it through the agent attache.
                            Request req = Request.parse(data);
                            agent.send(req, null);
                            return;
                        } else {
                            if (agent instanceof Routable) {
                                Routable cluster = (Routable) agent;
                                cluster.routeToAgent(data);
                            } else {
                                agent.send(Request.parse(data));
                            }
                            return;
                        }
                    } catch (AgentUnavailableException e) {
                        logD(data, e.getMessage());
                        cancel(Long.toString(Request.getManagementServerId(data)), hostId, Request.getSequence(data), e.getMessage());
                    }
                } else {

                    long mgmtId = Request.getManagementServerId(data);
                    if (mgmtId != -1 && mgmtId != _nodeId) {
                        routeToPeer(Long.toString(mgmtId), data);
                        if (Request.requiresSequentialExecution(data)) {
                            AgentAttache attache = (AgentAttache) link.attachment();
                            if (attache != null) {
                                attache.sendNext(Request.getSequence(data));
                            } else if (s_logger.isDebugEnabled()) {
                                logD(data, "No attache to process " + Request.parse(data).toString());
                            }
                        }
                        return;
                    } else {
                        if (Request.isRequest(data)) {
                            super.doTask(task);
                        } else {
                            // received an answer.
                            final Response response = Response.parse(data);
                            AgentAttache attache = findAttache(response.getAgentId());
                            if (attache == null) {
                                s_logger.info("SeqA " + response.getAgentId() + "-" + response.getSequence() + "Unable to find attache to forward " + response.toString());
                                return;
                            }
                            if (!attache.processAnswers(response.getSequence(), response)) {
                                s_logger.info("SeqA " + attache.getId() + "-" + response.getSequence() + ": Response is not processed: " + response.toString());
                            }
                        }
                        return;
                    }
                }
            } finally {
                txn.close();
            }
        }
    }

    @Override
    public void onManagementNodeJoined(List<ManagementServerHostVO> nodeList, long selfNodeId) {
    }

    @Override
    public void onManagementNodeLeft(List<ManagementServerHostVO> nodeList, long selfNodeId) {
        for (ManagementServerHostVO vo : nodeList) {
            s_logger.info("Marking hosts as disconnected on Management server" + vo.getMsid());
            _hostDao.markHostsAsDisconnected(vo.getMsid());
        }
    }

    @Override
    public void onManagementNodeIsolated() {
    }

    @Override
    public void removeAgent(AgentAttache attache, Status nextState) {
        if (attache == null) {
            return;
        }

        super.removeAgent(attache, nextState);
    }

    @Override
    public boolean executeRebalanceRequest(long agentId, long currentOwnerId, long futureOwnerId, Event event) throws AgentUnavailableException, OperationTimedoutException {
        if (event == Event.RequestAgentRebalance) {
            return setToWaitForRebalance(agentId, currentOwnerId, futureOwnerId);
        } else if (event == Event.StartAgentRebalance) {
            return rebalanceHost(agentId, currentOwnerId, futureOwnerId);
        } 

        return true;
    }
    
    @Override
    public void scheduleRebalanceAgents() {
        _timer.schedule(new AgentLoadBalancerTask(), 30000);
    }

    public class AgentLoadBalancerTask extends TimerTask {
        protected volatile boolean cancelled = false;

        public AgentLoadBalancerTask() {
            s_logger.debug("Agent load balancer task created");
        }

        @Override
        public synchronized boolean cancel() {
            if (!cancelled) {
                cancelled = true;
                s_logger.debug("Agent load balancer task cancelled");
                return super.cancel();
            }
            return true;
        }

        @Override
        public synchronized void run() {
            if (!cancelled) {
                startRebalanceAgents();
                if (s_logger.isInfoEnabled()) {
                    s_logger.info("The agent load balancer task is now being cancelled");
                }
                cancelled = true;
            }
        }
    }
    
   
    public void startRebalanceAgents() {
        s_logger.debug("Management server " + _nodeId + " is asking other peers to rebalance their agents");
        List<ManagementServerHostVO> allMS = _mshostDao.listBy(ManagementServerHost.State.Up);
        List<HostVO> allManagedAgents = _hostDao.listManagedRoutingAgents();

        int avLoad = 0;

        if (!allManagedAgents.isEmpty() && !allMS.isEmpty()) {
            avLoad = allManagedAgents.size() / allMS.size();
        } else {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("There are no hosts to rebalance in the system. Current number of active management server nodes in the system is " + allMS.size() + "; number of managed agents is " + allManagedAgents.size());
            }
            return;
        }
        
        if (avLoad == 0L) {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("As calculated average load is less than 1, rounding it to 1");
            }
            avLoad = 1;
        }

        for (ManagementServerHostVO node : allMS) {
            if (node.getMsid() != _nodeId) {
                
                List<HostVO> hostsToRebalance = new ArrayList<HostVO>();
                for (AgentLoadBalancerPlanner lbPlanner : _lbPlanners) {
                    hostsToRebalance = lbPlanner.getHostsToRebalance(node.getMsid(), avLoad);
                    if (hostsToRebalance != null && !hostsToRebalance.isEmpty()) {
                        break;
                    } else {
                        s_logger.debug("Agent load balancer planner " + lbPlanner.getName() + " found no hosts to be rebalanced from management server " + node.getMsid());
                    }
                }

                if (hostsToRebalance != null && !hostsToRebalance.isEmpty()) {
                    for (HostVO host : hostsToRebalance) {
                        long hostId = host.getId();
                        s_logger.debug("Asking management server " + node.getMsid() + " to give away host id=" + hostId);
                        boolean result = true;
                        
                        if (_hostTransferDao.findById(hostId) != null) {
                            s_logger.warn("Somebody else is already rebalancing host id: " + hostId);
                            continue;
                        }
                        
                        HostTransferMapVO transfer = _hostTransferDao.startAgentTransfering(hostId, node.getMsid(), _nodeId);
                        try {
                            Answer[] answer = sendRebalanceCommand(node.getMsid(), hostId, node.getMsid(), _nodeId, Event.RequestAgentRebalance);
                            if (answer == null) {
                                s_logger.warn("Failed to get host id=" + hostId + " from management server " + node.getMsid());
                                result = false;
                            }
                        } catch (Exception ex) {
                            s_logger.warn("Failed to get host id=" + hostId + " from management server " + node.getMsid(), ex);
                            result = false;
                        } finally {
                            HostTransferMapVO transferState = _hostTransferDao.findByIdAndFutureOwnerId(transfer.getId(), _nodeId);
                            if (!result && transferState != null && transferState.getState() == HostTransferState.TransferRequested) {
                                if (s_logger.isDebugEnabled()) {
                                    s_logger.debug("Removing mapping from op_host_transfer as it failed to be set to transfer mode");
                                }
                                //just remove the mapping as nothing was done on the peer management server yet
                                _hostTransferDao.remove(transfer.getId());
                            }
                        }
                    }
                } else {
                    s_logger.debug("Found no hosts to rebalance from the management server " + node.getMsid());
                }
            }
        }
    }

    private Answer[] sendRebalanceCommand(long peer, long agentId, long currentOwnerId, long futureOwnerId, Event event) {
        TransferAgentCommand transfer = new TransferAgentCommand(agentId, currentOwnerId, futureOwnerId, event);
        Commands commands = new Commands(OnError.Stop);
        commands.addCommand(transfer);

        Command[] cmds = commands.toCommands();

        try {
            if (s_logger.isDebugEnabled()) {
                s_logger.debug("Forwarding " + cmds[0].toString() + " to " + peer);
            }
            String peerName = Long.toString(peer);
            Answer[] answers = _clusterMgr.execute(peerName, agentId, cmds, true);
            return answers;
        } catch (Exception e) {
            s_logger.warn("Caught exception while talking to " + currentOwnerId, e);
            return null;
        }
    }

    private Runnable getTransferScanTask() {
        return new Runnable() {
            @Override
            public void run() {
                try {
                    if (s_logger.isTraceEnabled()) {
                        s_logger.trace("Clustered agent transfer scan check, management server id:" + _nodeId);
                    }

                    if (_agentToTransferIds.size() > 0) {
                        s_logger.debug("Found " + _agentToTransferIds.size() + " agents to transfer");
                        for (Long hostId : _agentToTransferIds) {
                            AgentAttache attache = findAttache(hostId);
                            
                            // if the thread:
                            // 1) timed out waiting for the host to reconnect
                            // 2) recipient management server is not active any more
                            // remove the host from re-balance list and delete from op_host_transfer DB
                            // no need to do anything with the real attache as we haven't modified it yet
                            Date cutTime = DateUtil.currentGMTTime();
                            if (_hostTransferDao.isNotActive(hostId, new Date(cutTime.getTime() - rebalanceTimeOut))) {
                                s_logger.debug("Timed out waiting for the host id=" + hostId + " to be ready to transfer, skipping rebalance for the host");
                                failStartRebalance(hostId);
                                return;
                            }  
                            
                            HostTransferMapVO transferMap = _hostTransferDao.findByIdAndCurrentOwnerId(hostId, _nodeId);
                            
                            if (transferMap == null) {
                                s_logger.debug("Can't transfer host id=" + hostId + "; record for the host no longer exists in op_host_transfer table");
                                failStartRebalance(hostId);
                                return;
                            }
                            
                            ManagementServerHostVO ms = _mshostDao.findByMsid(transferMap.getFutureOwner());
                            if (ms != null && ms.getState() != ManagementServerHost.State.Up) {
                                s_logger.debug("Can't transfer host " + hostId + " as it's future owner is not in UP state: " + ms + ", skipping rebalance for the host");
                                failStartRebalance(hostId);
                                return;
                            } 
                            
                            if (attache.getQueueSize() == 0 && attache.getNonRecurringListenersSize() == 0) {
                                rebalanceHost(hostId, transferMap.getInitialOwner(), transferMap.getFutureOwner());
                            } else {
                                s_logger.debug("Agent " + hostId + " can't be transfered yet as its request queue size is " + attache.getQueueSize() + " and listener queue size is " + attache.getNonRecurringListenersSize()); 
                            }
                            
                        }
                    } else {
                        if (s_logger.isTraceEnabled()) {
                            s_logger.trace("Found no agents to be transfered by the management server " + _nodeId);
                        }
                    }

                } catch (Throwable e) {
                    s_logger.error("Problem with the clustered agent transfer scan check!", e);
                }
            }
        };
    }
    
    
    private boolean setToWaitForRebalance(final long hostId, long currentOwnerId, long futureOwnerId) {
        s_logger.debug("Adding agent " + hostId + " to the list of agents to transfer");
        synchronized (_agentToTransferIds) {
            return  _agentToTransferIds.add(hostId);
        }
    }
    
    
    protected boolean rebalanceHost(final long hostId, long currentOwnerId, long futureOwnerId) throws AgentUnavailableException{

        boolean result = true;
        if (currentOwnerId == _nodeId) {
            _agentToTransferIds.remove(hostId);
            if (!startRebalance(hostId)) {
                s_logger.debug("Failed to start agent rebalancing");
                failRebalance(hostId);
                return false;
            }
            try {
                Answer[] answer = sendRebalanceCommand(futureOwnerId, hostId, currentOwnerId, futureOwnerId, Event.StartAgentRebalance);
                if (answer == null || !answer[0].getResult()) {
                    s_logger.warn("Host " + hostId + " failed to connect to the  management server " + futureOwnerId + " as a part of rebalance process");
                    result = false;
                }

            } catch (Exception ex) {
                s_logger.warn("Host " + hostId + " failed to connect to the  management server " + futureOwnerId + " as a part of rebalance process", ex);
                result = false;
            }
            
            if (result) {
                s_logger.debug("Got host id=" + hostId + " from management server " + futureOwnerId);
                finishRebalance(hostId, futureOwnerId, Event.RebalanceCompleted);
            } else {
                finishRebalance(hostId, futureOwnerId, Event.RebalanceFailed);
            }
                
        } else if (futureOwnerId == _nodeId) {
            HostVO host = _hostDao.findById(hostId);
            try {
                if (s_logger.isDebugEnabled()) {
                    s_logger.debug("Loading directly connected host " + host.getId() + "(" + host.getName() + ") as a part of rebalance process");
                }
                result = loadDirectlyConnectedHost(host, true);
            } catch (Exception ex) {
                s_logger.warn("Unable to load directly connected host " + host.getId() + " as a part of rebalance due to exception: ", ex);
                result = false;
            }
        }

        return result;
    }
    

    protected void finishRebalance(final long hostId, long futureOwnerId, Event event) throws AgentUnavailableException{

        boolean success = (event == Event.RebalanceCompleted) ? true : false;
        if (s_logger.isDebugEnabled()) {
            s_logger.debug("Finishing rebalancing for the agent " + hostId + " with result " + success);
        }
        
        AgentAttache attache = findAttache(hostId);
        if (attache == null || !(attache instanceof ClusteredAgentAttache)) {
            s_logger.debug("Unable to find forward attache for the host id=" + hostId + ", assuming that the agent disconnected already");
            _hostTransferDao.completeAgentTransfer(hostId);
            return;
        } 
        
        ClusteredAgentAttache forwardAttache = (ClusteredAgentAttache)attache;
        
        if (success) {
            //1) Set transfer mode to false - so the agent can start processing requests normally
            forwardAttache.setTransferMode(false);
            
            //2) Get all transfer requests and route them to peer
            Request requestToTransfer = forwardAttache.getRequestToTransfer();
            while (requestToTransfer != null) {
                s_logger.debug("Forwarding request " + requestToTransfer.getSequence() + " held in transfer attache " + hostId + " from the management server " + _nodeId + " to " + futureOwnerId);
                boolean routeResult = routeToPeer(Long.toString(futureOwnerId), requestToTransfer.getBytes());
                if (!routeResult) {
                    logD(requestToTransfer.getBytes(), "Failed to route request to peer");
                }
                
                requestToTransfer = forwardAttache.getRequestToTransfer();
            }
            s_logger.debug("Management server " + _nodeId + " completed agent " + hostId + " rebalance");
           
        } else {
            failRebalance(hostId);
        }
        
        _hostTransferDao.completeAgentTransfer(hostId);
    }
    
    protected void failRebalance(final long hostId) throws AgentUnavailableException{
        s_logger.debug("Management server " + _nodeId + " failed to rebalance agent " + hostId);
        _hostTransferDao.completeAgentTransfer(hostId);
        reconnect(hostId);
    }
    
    @DB
    protected boolean startRebalance(final long hostId) {
        HostVO host = _hostDao.findById(hostId);
        
        if (host == null || host.getRemoved() != null) {
            s_logger.warn("Unable to find host record, fail start rebalancing process");
            return false;
        } 
        
        synchronized (_agents) {
            ClusteredDirectAgentAttache attache = (ClusteredDirectAgentAttache)_agents.get(hostId);
            if (attache != null && attache.getQueueSize() == 0 && attache.getNonRecurringListenersSize() == 0) {
                _agentToTransferIds.remove(hostId);
                removeAgent(attache, Status.Rebalancing);
                ClusteredAgentAttache forwardAttache = (ClusteredAgentAttache)createAttache(hostId);
                if (forwardAttache == null) {
                    s_logger.warn("Unable to create a forward attache for the host " + hostId + " as a part of rebalance process");
                    return false;
                }
                s_logger.debug("Putting agent id=" + hostId + " to transfer mode");
                forwardAttache.setTransferMode(true);
                _agents.put(hostId, forwardAttache);
            } else {
                if (attache == null) {
                    s_logger.warn("Attache for the agent " + hostId + " no longer exists on management server " + _nodeId + ", can't start host rebalancing");
                } else {
                    s_logger.warn("Attache for the agent " + hostId + " has request queue size= " + attache.getQueueSize() + " and listener queue size " + attache.getNonRecurringListenersSize() + ", can't start host rebalancing");
                }
                return false;
            }
        }
        
        Transaction txn = Transaction.currentTxn();
        txn.start();
        
        s_logger.debug("Updating host id=" + hostId + " with the status " + Status.Rebalancing);
        host.setManagementServerId(null);
        _hostDao.updateStatus(host, Event.StartAgentRebalance, _nodeId);
        _hostTransferDao.startAgentTransfer(hostId);
        txn.commit();
        
        return true;
    }
    
    protected void failStartRebalance(final long hostId) {
        _agentToTransferIds.remove(hostId);
        _hostTransferDao.completeAgentTransfer(hostId);
    }
    
    protected void cleanupTransferMap() {
        List<HostTransferMapVO> hostsJoingingCluster = _hostTransferDao.listHostsJoiningCluster(_nodeId);
        
        for (HostTransferMapVO hostJoingingCluster : hostsJoingingCluster) {
            _hostTransferDao.remove(hostJoingingCluster.getId());
        }
        
        List<HostTransferMapVO> hostsLeavingCluster = _hostTransferDao.listHostsLeavingCluster(_nodeId);
        for (HostTransferMapVO hostLeavingCluster : hostsLeavingCluster) {
            _hostTransferDao.remove(hostLeavingCluster.getId());
        }
    }
    
}
