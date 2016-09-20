/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.yarn.server.resourcemanager.rmnode;

import io.hops.metadata.yarn.entity.PendingEvent;
import io.hops.util.DBUtility;
import io.hops.util.ToCommitHB;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.net.Node;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.hadoop.yarn.api.records.ApplicationId;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.ContainerState;
import org.apache.hadoop.yarn.api.records.ContainerStatus;
import org.apache.hadoop.yarn.api.records.NodeId;
import org.apache.hadoop.yarn.api.records.NodeState;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceOption;
import org.apache.hadoop.yarn.server.api.protocolrecords.NMContainerStatus;
import org.apache.hadoop.yarn.server.api.protocolrecords.NodeHeartbeatResponse;
import org.apache.hadoop.yarn.server.api.records.NodeHealthStatus;
import org.apache.hadoop.yarn.server.resourcemanager.ClusterMetrics;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.NodesListManagerEventType;
import org.apache.hadoop.yarn.server.resourcemanager.RMContext;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMApp;
import org.apache.hadoop.yarn.server.resourcemanager.rmapp.RMAppRunningOnNodeEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeAddedSchedulerEvent;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.event.NodeResourceUpdateSchedulerEvent;
import org.apache.hadoop.yarn.state.InvalidStateTransitonException;

public class RMNodeImplDist extends RMNodeImpl {

  private static final Log LOG = LogFactory.getLog(RMNodeImplDist.class);
  private ToCommitHB toCommit = new ToCommitHB(this.nodeId.toString());

  // Used by RT streaming receiver
  public static enum KeyType {
    CURRENTNMTOKENMASTERKEY,
    NEXTNMTOKENMASTERKEY,
    CURRENTCONTAINERTOKENMASTERKEY,
    NEXTCONTAINERTOKENMASTERKEY
  }

  public RMNodeImplDist(NodeId nodeId, RMContext context, String hostName,
          int cmPort, int httpPort, Node node, Resource capability,
          String nodeManagerVersion) {
    super(nodeId, context, hostName, cmPort, httpPort, node, capability,
            nodeManagerVersion);
  }

  protected NodeState statusUpdateWhenHealthyTransitionInternal(
          RMNodeImpl rmNode, RMNodeEvent event) {
    RMNodeStatusEvent statusEvent = (RMNodeStatusEvent) event;

    // Switch the last heartbeatresponse.
    rmNode.latestNodeHeartBeatResponse = statusEvent.getLatestResponse();

    NodeHealthStatus remoteNodeHealthStatus = statusEvent.
            getNodeHealthStatus();
    rmNode.setHealthReport(remoteNodeHealthStatus.getHealthReport());
    rmNode.setLastHealthReportTime(
            remoteNodeHealthStatus.getLastHealthReportTime());
    if (!remoteNodeHealthStatus.getIsNodeHealthy()) {
      LOG.info("Node " + rmNode.nodeId + " reported UNHEALTHY with details: "
              + remoteNodeHealthStatus.getHealthReport());
      rmNode.nodeUpdateQueue.clear();
      // Inform the scheduler
//      if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
        //Add NodeRemovedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        toCommit.addPendingEvent(PendingEvent.Type.NODE_REMOVED,
                PendingEvent.Status.NEW);
//      } else {
//        rmNode.context.getDispatcher().getEventHandler().handle(
//                new NodeRemovedSchedulerEvent(rmNode));
      if(rmNode.context.isLeader()){
        rmNode.context.getDispatcher().getEventHandler().handle(
                new NodesListManagerEvent(
                        NodesListManagerEventType.NODE_UNUSABLE, rmNode));
      }

      // Update metrics
      rmNode.updateMetricsForDeactivatedNode(rmNode.getState(),
              NodeState.UNHEALTHY);
      return NodeState.UNHEALTHY;
    }
    ((RMNodeImplDist) rmNode).handleContainerStatus(statusEvent.
            getContainers());

    if (rmNode.nextHeartBeat) {
      rmNode.nextHeartBeat = false;
      toCommit.addNextHeartBeat(rmNode.nextHeartBeat);
//      if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
        //Add NodeUpdatedSchedulerEvent to TransactionState
        toCommit.addPendingEvent(PendingEvent.Type.NODE_UPDATED,
                PendingEvent.Status.SCHEDULER_FINISHED_PROCESSING);
//      } else {
//        rmNode.context.getDispatcher().getEventHandler().handle(
//                new NodeUpdateSchedulerEvent(rmNode));
//      }
    } else if (rmNode.context.isDistributed() 
//            && !rmNode.context.isLeader()
            ) {

      toCommit.addPendingEvent(PendingEvent.Type.NODE_UPDATED,
              PendingEvent.Status.SCHEDULER_NOT_FINISHED_PROCESSING);
    }

    // Update DTRenewer in secure mode to keep these apps alive. Today this is
    // needed for log-aggregation to finish long after the apps are gone.
    if (UserGroupInformation.isSecurityEnabled()) {
      rmNode.context.getDelegationTokenRenewer().updateKeepAliveApplications(
              statusEvent.getKeepAliveAppIds());
    }
    toCommit.addRMNode(hostName, commandPort, httpPort, totalCapability,
            nodeManagerVersion, getState(), getHealthReport(),
            getLastHealthReportTime());
    return NodeState.RUNNING;

  }

  protected void handleContainerStatus(List<ContainerStatus> containerStatuses) {
    // Filter the map to only obtain just launched containers and finished
    // containers.
    List<ContainerStatus> newlyLaunchedContainers
            = new ArrayList<ContainerStatus>();
    List<ContainerStatus> completedContainers = new ArrayList<ContainerStatus>();
    for (ContainerStatus remoteContainer : containerStatuses) {
      ContainerId containerId = remoteContainer.getContainerId();

      // Don't bother with containers already scheduled for cleanup, or for
      // applications already killed. The scheduler doens't need to know any
      // more about this container
      if (containersToClean.contains(containerId)) {
        LOG.info("Container " + containerId + " already scheduled for "
                + "cleanup, no further processing");
        continue;
      }
      if (finishedApplications.contains(containerId.getApplicationAttemptId()
              .getApplicationId())) {
        LOG.info("Container " + containerId
                + " belongs to an application that is already killed,"
                + " no further processing");
        continue;
      }

      // Process running containers
      if (remoteContainer.getState() == ContainerState.RUNNING) {
        if (!launchedContainers.contains(containerId)) {
          // Just launched container. RM knows about it the first time.
          launchedContainers.add(containerId);
          newlyLaunchedContainers.add(remoteContainer);
        }
      } else {
        // A finished container
        launchedContainers.remove(containerId);
        completedContainers.add(remoteContainer);
      }
    }
    if (newlyLaunchedContainers.size() != 0 || completedContainers.size() != 0) {
      UpdatedContainerInfo uci = new UpdatedContainerInfo(
              newlyLaunchedContainers,
              completedContainers);
      toCommit.addNodeUpdateQueue(uci);
    }
  }

  @Override
  public void updateNodeHeartbeatResponseForCleanup(
          NodeHeartbeatResponse response) {
    this.writeLock.lock();

    try {
      response.addAllContainersToCleanup(
              new ArrayList<ContainerId>(this.containersToClean));
      response.addAllApplicationsToCleanup(this.finishedApplications);
      response.addContainersToBeRemovedFromNM(
              new ArrayList<ContainerId>(this.containersToBeRemovedFromNM));

      // We need to make a deep copy of containersToClean and finishedApplications
      // since DBUtility is async and we get ConcurrentModificationException
      Set<ContainerId> copyContainersToClean = new HashSet<>(this.containersToClean.size());
      for (ContainerId cid : this.containersToClean) {
        copyContainersToClean.add(ContainerId.newContainerId(cid.getApplicationAttemptId(),
                cid.getContainerId()));
      }
      DBUtility.removeContainersToClean(copyContainersToClean, this.nodeId);

      List<ApplicationId> copyFinishedApplications = new ArrayList<>(this.finishedApplications.size());
      for (ApplicationId appId : this.finishedApplications) {
        copyFinishedApplications.add(ApplicationId.newInstance(appId.getClusterTimestamp(),
                appId.getId()));
      }
      DBUtility.removeFinishedApplications(copyFinishedApplications, this.nodeId);
      this.containersToClean.clear();
      this.finishedApplications.clear();
      this.containersToBeRemovedFromNM.clear();
    } catch (IOException ex) {
      LOG.error(ex, ex);
    } finally {
      this.writeLock.unlock();
    }
  }

  protected void handleRunningAppOnNode(RMNodeImpl rmNode,
          RMContext context, ApplicationId appId, NodeId nodeId) {
    RMApp app = context.getRMApps().get(appId);

    // if we failed getting app by appId, maybe something wrong happened, just
    // add the app to the finishedApplications list so that the app can be
    // cleaned up on the NM
    if (null == app) {
      LOG.warn("Cannot get RMApp by appId=" + appId
              + ", just added it to finishedApplications list for cleanup");
      rmNode.finishedApplications.add(appId);
      try {
        DBUtility.addFinishedApplication(appId, rmNode.nodeId);
      } catch (IOException ex) {
        LOG.error(ex, ex);
      }
      return;
    }

    context.getDispatcher().getEventHandler()
            .handle(new RMAppRunningOnNodeEvent(appId, nodeId));
  }

  @Override
  protected void cleanUpAppTransitionInternal(RMNodeImpl rmNode,
          RMNodeEvent event) {
    rmNode.finishedApplications.add(((RMNodeCleanAppEvent) event).getAppId());
    try {
      DBUtility.addFinishedApplication(((RMNodeCleanAppEvent) event).
              getAppId(),
              rmNode.getNodeID());
    } catch (IOException ex) {
      LOG.error(ex, ex);
  }
  }

  protected void cleanUpContainerTransitionInternal(RMNodeImpl rmNode,
          RMNodeEvent event) {
    rmNode.containersToClean.add(((RMNodeCleanContainerEvent) event).
            getContainerId());
    try {
      DBUtility.addContainerToClean(((RMNodeCleanContainerEvent) event).
              getContainerId(), rmNode.getNodeID());
    } catch (IOException ex) {
      LOG.error(ex, ex);
  }
  }

  @Override
  public List<UpdatedContainerInfo> pullContainerUpdates() {
    List<UpdatedContainerInfo> latestContainerInfoList
            = new ArrayList<UpdatedContainerInfo>();
    try {
      UpdatedContainerInfo containerInfo;
      while ((containerInfo = nodeUpdateQueue.poll()) != null) {
        latestContainerInfoList.add(containerInfo);
      }
      DBUtility.removeUCI(latestContainerInfoList, this.nodeId.toString());
      this.nextHeartBeat = true;
      DBUtility.addNextHB(this.nextHeartBeat, this.nodeId.toString());
    } catch (IOException ex) {
      LOG.error(ex, ex);
    }
    return latestContainerInfoList;
  }

  public void setContainersToCleanUp(Set<ContainerId> containersToCleanUp) {
    super.writeLock.lock();

    try {
      super.containersToClean.addAll(containersToCleanUp);
    } finally {
      super.writeLock.unlock();
    }
  }

  public void addContainersToCleanUp(ContainerId containerToCleanUp) {
    super.writeLock.lock();

    try {
      super.containersToClean.add(containerToCleanUp);
    } finally {
      super.writeLock.unlock();
    }
  }
  
  public void setAppsToCleanUp(List<ApplicationId> appsToCleanUp) {
    super.writeLock.lock();

    try {
      super.finishedApplications.addAll(appsToCleanUp);
    } finally {
      super.writeLock.unlock();
    }
  }

  public void addAppToCleanUp(ApplicationId appToCleanUp) {
    super.writeLock.lock();

    try {
      super.finishedApplications.add(appToCleanUp);
    } finally {
      super.writeLock.unlock();
    }
  }
  
  public void setNextHeartbeat(boolean nextHeartbeat) {
    super.writeLock.lock();

    try {
      super.nextHeartBeat = nextHeartbeat;
    } finally {
      super.writeLock.unlock();
    }
  }

  public void setState(String state) {
    super.writeLock.lock();
    try {
      super.stateMachine.setCurrentState(NodeState.valueOf(state));
    } finally {
      super.writeLock.unlock();
    }
  }

  public void setUpdatedContainerInfo(ConcurrentLinkedQueue<UpdatedContainerInfo>
          updatedContainerInfo) {
    super.nodeUpdateQueue.addAll(updatedContainerInfo);
  }

  @Override
  protected void addNodeTransitionInternal(RMNodeImpl rmNode, RMNodeEvent event) {
    // Inform the scheduler
    RMNodeStartedEvent startEvent = (RMNodeStartedEvent) event;
    List<NMContainerStatus> containers = null;

    String host = rmNode.nodeId.getHost();
    if (rmNode.context.getInactiveRMNodes().containsKey(host)) {
      // Old node rejoining
      RMNode previouRMNode = rmNode.context.getInactiveRMNodes().get(host);
      rmNode.context.getInactiveRMNodes().remove(host);
      rmNode.updateMetricsForRejoinedNode(previouRMNode.getState());
    } else {
      // Increment activeNodes explicitly because this is a new node.
      ClusterMetrics.getMetrics().incrNumActiveNodes();
      containers = startEvent.getNMContainerStatuses();
      if (containers != null && !containers.isEmpty()) {
        for (NMContainerStatus container : containers) {
          if (container.getContainerState() == ContainerState.RUNNING) {
            rmNode.launchedContainers.add(container.getContainerId());
          }
        }
      }
    }

    if (null != startEvent.getRunningApplications()) {
      for (ApplicationId appId : startEvent.getRunningApplications()) {
        rmNode.handleRunningAppOnNode(rmNode, rmNode.context, appId,
                rmNode.nodeId);
      }
    }

//    if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
      //Add NodeAddedSchedulerEvent to TransactionState
      toCommit.addPendingEvent(PendingEvent.Type.NODE_ADDED,
              PendingEvent.Status.NEW);
//    } else {
//      rmNode.context.getDispatcher().getEventHandler()
//              .handle(new NodeAddedSchedulerEvent(rmNode, containers));
if(rmNode.context.isLeader()){
      rmNode.context.getDispatcher().getEventHandler().handle(
              new NodesListManagerEvent(
                      NodesListManagerEventType.NODE_USABLE, rmNode));
    }
  }

  protected void reconnectNodeTransitionInternal(RMNodeImpl rmNode,
          RMNodeEvent event) {
    RMNodeReconnectEvent reconnectEvent = (RMNodeReconnectEvent) event;
    RMNode newNode = reconnectEvent.getReconnectedNode();
    rmNode.nodeManagerVersion = newNode.getNodeManagerVersion();
    List<ApplicationId> runningApps = reconnectEvent.getRunningApplications();
    boolean noRunningApps = (runningApps == null) || (runningApps.size() == 0);

    // No application running on the node, so send node-removal event with 
    // cleaning up old container info.
    if (noRunningApps) {
      rmNode.nodeUpdateQueue.clear();
//      if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
        //Add NodeRemovedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        toCommit.addPendingEvent(PendingEvent.Type.NODE_REMOVED,
                PendingEvent.Status.NEW);
//      } else {
//        rmNode.context.getDispatcher().getEventHandler().handle(
//                new NodeRemovedSchedulerEvent(rmNode));
//      }
      if (rmNode.getHttpPort() == newNode.getHttpPort()) {
        if (!rmNode.getTotalCapability().equals(
                newNode.getTotalCapability())) {
          rmNode.totalCapability = newNode.getTotalCapability();
        }
        if (rmNode.getState().equals(NodeState.RUNNING)) {
          // Only add old node if old state is RUNNING
          if (rmNode.context.isDistributed() 
//                  && !rmNode.context.isLeader()
                  ) {
            //Add NodeAddedSchedulerEvent to TransactionState
            LOG.debug("HOP :: Added Pending event to TransactionState");
            toCommit.addPendingEvent(PendingEvent.Type.NODE_ADDED,
                    PendingEvent.Status.NEW);
          } else {
            rmNode.context.getDispatcher().getEventHandler().handle(
                    new NodeAddedSchedulerEvent(rmNode));
          }
        }
      } else {
        // Reconnected node differs, so replace old node and start new node
        switch (rmNode.getState()) {
          case RUNNING:
            ClusterMetrics.getMetrics().decrNumActiveNodes();
            break;
          case UNHEALTHY:
            ClusterMetrics.getMetrics().decrNumUnhealthyNMs();
            break;
          default:
            LOG.debug("Unexpected Rmnode state");
        }
        rmNode.context.getRMNodes().put(newNode.getNodeID(), newNode);
        rmNode.context.getDispatcher().getEventHandler().handle(
                new RMNodeStartedEvent(newNode.getNodeID(), null, null));
      }
    } else {
      rmNode.httpPort = newNode.getHttpPort();
      rmNode.httpAddress = newNode.getHttpAddress();
      boolean isCapabilityChanged = false;
      if (!rmNode.getTotalCapability().equals(
              newNode.getTotalCapability())) {
        rmNode.totalCapability = newNode.getTotalCapability();
        isCapabilityChanged = true;
      }

      handleNMContainerStatus(reconnectEvent.getNMContainerStatuses(), rmNode);

      for (ApplicationId appId : reconnectEvent.getRunningApplications()) {
        rmNode.handleRunningAppOnNode(rmNode, rmNode.context, appId,
                rmNode.nodeId);
      }

      if (isCapabilityChanged
              && rmNode.getState().equals(NodeState.RUNNING)) {
        // Update scheduler node's capacity for reconnect node.
        rmNode.context
                .getDispatcher()
                .getEventHandler()
                .handle(
                        new NodeResourceUpdateSchedulerEvent(rmNode,
                                ResourceOption
                                .newInstance(newNode.getTotalCapability(), -1)));
      }
    }
  }

  @Override
  protected void deactivateNodeTransitionInternal(RMNodeImpl rmNode,
          RMNodeEvent event, final NodeState finalState) {
    // Inform the scheduler
    rmNode.nodeUpdateQueue.clear();
    // If the current state is NodeState.UNHEALTHY
    // Then node is already been removed from the
    // Scheduler
    NodeState initialState = rmNode.getState();
    if (!initialState.equals(NodeState.UNHEALTHY)) {
//      if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
        //Add NodeRemovedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        toCommit.addPendingEvent(PendingEvent.Type.NODE_REMOVED,
                PendingEvent.Status.NEW);
//      } else {
//        rmNode.context.getDispatcher().getEventHandler()
//                .handle(new NodeRemovedSchedulerEvent(rmNode));
//      }
    }
    if(rmNode.context.isLeader()){
    rmNode.context.getDispatcher().getEventHandler().handle(
            new NodesListManagerEvent(
                    NodesListManagerEventType.NODE_UNUSABLE, rmNode));
    }
    // Deactivate the node
    rmNode.context.getRMNodes().remove(rmNode.nodeId);
    LOG.info("Deactivating Node " + rmNode.nodeId + " as it is now "
            + finalState);
    rmNode.context.getInactiveRMNodes().put(rmNode.nodeId.getHost(), rmNode);

    //Update the metrics
    rmNode.updateMetricsForDeactivatedNode(initialState, finalState);
  }

  protected NodeState statusUpdateWhenUnHealthyTransitionInternal(
          RMNodeImpl rmNode, RMNodeEvent event) {
    RMNodeStatusEvent statusEvent = (RMNodeStatusEvent) event;

    // Switch the last heartbeatresponse.
    rmNode.latestNodeHeartBeatResponse = statusEvent.getLatestResponse();
    NodeHealthStatus remoteNodeHealthStatus = statusEvent.getNodeHealthStatus();
    rmNode.setHealthReport(remoteNodeHealthStatus.getHealthReport());
    rmNode.setLastHealthReportTime(
            remoteNodeHealthStatus.getLastHealthReportTime());
    if (remoteNodeHealthStatus.getIsNodeHealthy()) {
//      if (rmNode.context.isDistributed() && !rmNode.context.isLeader()) {
        //Add NodeAddedSchedulerEvent to TransactionState
        LOG.debug("HOP :: Added Pending event to TransactionState");
        toCommit.addPendingEvent(PendingEvent.Type.NODE_ADDED,
                PendingEvent.Status.NEW);

//      } else {
//        rmNode.context.getDispatcher().getEventHandler().handle(
//                new NodeAddedSchedulerEvent(rmNode));
if(rmNode.context.isLeader()){
        rmNode.context.getDispatcher().getEventHandler().handle(
                new NodesListManagerEvent(
                        NodesListManagerEventType.NODE_USABLE, rmNode));
      }
      // ??? how about updating metrics before notifying to ensure that
      // notifiers get update metadata because they will very likely query it
      // upon notification
      // Update metrics
      rmNode.updateMetricsForRejoinedNode(NodeState.UNHEALTHY);
      return NodeState.RUNNING;
    }

    return NodeState.UNHEALTHY;
  }

  public void handle(RMNodeEvent event) {
    LOG.debug("Processing " + event.getNodeId() + " of type " + event.getType());
    try {
      writeLock.lock();
      NodeState oldState = getState();
      try {
        stateMachine.doTransition(event.getType(), event);
      } catch (InvalidStateTransitonException e) {
        LOG.error("Can't handle this event at current state", e);
        LOG.error("Invalid event " + event.getType() + " on Node  "
                + this.nodeId);
      }
      if (oldState != getState()) {
        LOG.info(nodeId + " Node Transitioned from " + oldState + " to "
                + getState());
        toCommit.addRMNode(hostName, commandPort, httpPort, totalCapability,
                nodeManagerVersion, getState(), getHealthReport(),
                getLastHealthReportTime());
      }
      try {
        toCommit.commit();
        toCommit = new ToCommitHB(this.nodeId.toString());
      } catch (IOException ex) {
        LOG.error(ex, ex);
      }
    } finally {
      writeLock.unlock();
    }
  }
}
