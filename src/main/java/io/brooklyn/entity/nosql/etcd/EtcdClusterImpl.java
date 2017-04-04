/*
 * Copyright 2014-2016 by Cloudsoft Corporation Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.brooklyn.entity.nosql.etcd;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.Location;
import org.apache.brooklyn.api.mgmt.Task;
import org.apache.brooklyn.api.policy.PolicySpec;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.entity.BrooklynConfigKeys;
import org.apache.brooklyn.core.entity.Entities;
import org.apache.brooklyn.core.entity.EntityPredicates;
import org.apache.brooklyn.core.entity.lifecycle.Lifecycle;
import org.apache.brooklyn.core.entity.lifecycle.ServiceStateLogic;
import org.apache.brooklyn.core.feed.ConfigToAttributes;
import org.apache.brooklyn.core.location.Locations;
import org.apache.brooklyn.core.sensor.DependentConfiguration;
import org.apache.brooklyn.entity.group.AbstractMembershipTrackingPolicy;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.group.DynamicClusterImpl;
import org.apache.brooklyn.util.collections.MutableList;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.collections.QuorumCheck;
import org.apache.brooklyn.util.collections.QuorumCheck.QuorumChecks;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.core.task.Tasks;
import org.apache.brooklyn.util.repeat.Repeater;
import org.apache.brooklyn.util.time.Duration;

public class EtcdClusterImpl extends DynamicClusterImpl implements EtcdCluster {

    private static final Logger LOG = LoggerFactory.getLogger(EtcdClusterImpl.class);

    private transient Object clusterMutex = new Object[0]; // For cluster join/leave operations

    @Override
    public Object getClusterMutex() { return clusterMutex; }

    @Override
    public String getIconUrl() { return "classpath://io.brooklyn.etcd.brooklyn-etcd:icons/etcd.png"; }

    @Override
    public void init() {
        sensors().set(NODE_ID, new AtomicInteger(0));
        ConfigToAttributes.apply(this, ETCD_NODE_SPEC);
        config().set(MEMBER_SPEC, sensors().get(ETCD_NODE_SPEC));
        config().set(UP_QUORUM_CHECK, QuorumChecks.allAndAtLeastOne());
        config().set(RUNNING_QUORUM_CHECK, QuorumChecks.allAndAtLeastOne());

        super.init();
    }

    @Override
    public void initEnrichers() {
        super.initEnrichers();

        // Include STARTING members in UP_QUORUM_CHECK as we want all members running and up for quorum
        enrichers().add(ServiceStateLogic.newEnricherFromChildrenUp()
                .checkMembersOnly()
                .requireUpChildren(getConfig(UP_QUORUM_CHECK))
                .configure(ServiceStateLogic.ComputeServiceIndicatorsFromChildrenAndMembers.IGNORE_ENTITIES_WITH_SERVICE_UP_NULL, false)
                .configure(ServiceStateLogic.ComputeServiceIndicatorsFromChildrenAndMembers.IGNORE_ENTITIES_WITH_THESE_SERVICE_STATES, ImmutableSet.<Lifecycle>of(Lifecycle.STOPPING, Lifecycle.STOPPED, Lifecycle.DESTROYED))
                .suppressDuplicates(true));
    }

    @Override
    public void start(Collection<? extends Location> locs) {
        addLocations(locs);
        List<Location> locations = MutableList.copyOf(Locations.getLocationsCheckingAncestors(locs, this));

        connectSensors();

        super.start(locations);
    }

    protected void connectSensors() {
        policies().add(PolicySpec.create(MemberTrackingPolicy.class)
                .displayName("EtcdCluster node tracker")
                .configure("sensorsToTrack", ImmutableSet.of(Attributes.HOSTNAME, EtcdNode.ETCD_NODE_INSTALLED))
                .configure("notifyOnDuplicates", Boolean.TRUE)
                .configure("group", this));
    }

    protected boolean isProxied() {
        String memberType = config().get(MEMBER_SPEC).getType().getSimpleName();
        return memberType.contains("Proxy");
    }

    protected void onServerPoolMemberChanged(final Entity member) {
        LOG.debug("For {}, considering membership of {} which is in locations {}", new Object[]{ this, member, member.getLocations() });

        Map<Entity, String> nodes = MutableMap.copyOf(sensors().get(ETCD_CLUSTER_NODES));
        Duration timeout = config().get(BrooklynConfigKeys.START_TIMEOUT);
        Entity firstNode = sensors().get(DynamicCluster.FIRST);

        if (belongsInServerPool(member) && !nodes.containsKey(member)) {
            EtcdNode node = (EtcdNode) member;
            String name = Preconditions.checkNotNull(node.getNodeName());
            // Ensure etcd has been installed
            Task<Boolean> installed = DynamicTasks.submit(DependentConfiguration.attributeWhenReady(member, EtcdNode.ETCD_NODE_INSTALLED), this).asTask();
            if (installed.blockUntilEnded(timeout) && installed.getUnchecked()) {
                // Check if we are first node in the cluster.
                if (node.equals(firstNode)) {
                    addNode(node, name);
                } else {
                    // Add node asynchronously
                    Callable<Void> joinCluster = new JoinCluster(firstNode, node, name, timeout);
                    DynamicTasks.submit(Tasks.create("Joining etcd cluster", joinCluster), this);
                }
            }
        }
    }

    protected class JoinCluster implements Callable<Void> {
        private final Entity firstNode;
        private final EtcdNode member;
        private final String name;
        private final Duration timeout;

        public JoinCluster(Entity firstNode, EtcdNode member, String name, Duration timeout) {
            this.firstNode = firstNode;
            this.member = member;
            this.name = name;
            this.timeout = timeout;
        }

        @Override
        public Void call() throws Exception {
            // Wait for first node to be ready
            Entities.waitForServiceUp(firstNode);
            Task<Boolean> joined = DynamicTasks.submit(DependentConfiguration.attributeWhenReady(firstNode, EtcdNode.ETCD_NODE_HAS_JOINED_CLUSTER), EtcdClusterImpl.this).asTask();
            if (joined.blockUntilEnded(timeout) && joined.getUnchecked()) {
                // Try invoking joinCluster effector
                synchronized (clusterMutex) {
                    boolean success = Repeater.create("Calling joinCluster effector")
                            .every(Duration.ONE_MINUTE)
                            .limitIterationsTo(3)
                            .limitTimeTo(timeout)
                            .until(new InvokeJoinEffector())
                            .run();
                    if (!success) {
                        throw new IllegalStateException(String.format("Node %s failed to join cluster %s", member, EtcdClusterImpl.this));
                    }
                }
            }
            return null;
        }

        protected class InvokeJoinEffector implements Callable<Boolean> {
            @Override
            public Boolean call() throws Exception {
                LOG.debug("Calling joinCluster effector on {} for {}", firstNode, member);
                if (member.hasJoinedCluster()) return true;
                String address = Preconditions.checkNotNull(getNodeAddress(member));
                if (Entities.invokeEffectorWithArgs(EtcdClusterImpl.this, firstNode, EtcdNode.JOIN_ETCD_CLUSTER, name, address).blockUntilEnded(timeout)) {
                    Duration.seconds(15).countdownTimer().waitForExpiryUnchecked();
                    addNode(member, name);
                    return true;
                } else {
                    return false;
                }
            }
        }
    }

    protected void onServerPoolMemberRemoved(final Entity member) {
        Map<Entity, String> nodes = MutableMap.copyOf(sensors().get(ETCD_CLUSTER_NODES));
        Duration timeout = config().get(BrooklynConfigKeys.START_TIMEOUT);
        String name = nodes.get(member);

        if (nodes.containsKey(member)) {
            synchronized (clusterMutex) {
                Optional<Entity> otherNode = Iterables.tryFind(nodes.keySet(), Predicates.and(
                        Predicates.instanceOf(EtcdNode.class),
                        EntityPredicates.attributeEqualTo(EtcdNode.ETCD_NODE_HAS_JOINED_CLUSTER, Boolean.TRUE),
                        Predicates.not(EntityPredicates.idEqualTo(member.getId()))));
                if (otherNode.isPresent()) {
                    boolean ended = Entities.invokeEffectorWithArgs(this, otherNode.get(), EtcdNode.LEAVE_ETCD_CLUSTER, name).blockUntilEnded(timeout);
                    if (!ended) {
                        LOG.warn("Timeout invoking leaveCluster for {} on {}", member, otherNode.get());
                    }
                }
                removeNode(member, name);
            }
        }
    }

    private void addNode(Entity member, String name) {
        synchronized (clusterMutex) {
            LOG.info("Adding node {}: {}; {} to cluster", new Object[] { this, member, name });

            Map<Entity, String> nodes = MutableMap.copyOf(sensors().get(ETCD_CLUSTER_NODES));
            nodes.put(member, name);
            recalculateClusterAddresses(nodes);

            member.sensors().set(EtcdNode.ETCD_NODE_HAS_JOINED_CLUSTER, Boolean.TRUE);
            Entities.waitForServiceUp(member);
        }
    }

    private void removeNode(Entity member, String name) {
        synchronized (clusterMutex) {
            LOG.info("Removing node {}: {}; {} from cluster", new Object[] { this, member, name });

            Map<Entity, String> nodes = MutableMap.copyOf(sensors().get(ETCD_CLUSTER_NODES));
            nodes.remove(member);
            recalculateClusterAddresses(nodes);

            member.sensors().set(EtcdNode.ETCD_NODE_HAS_JOINED_CLUSTER, Boolean.FALSE);
        }
    }

    private void recalculateClusterAddresses(Map<Entity, String> nodes) {
        Map<String,String> addresses = Maps.newHashMap();
        for (Entity entity : nodes.keySet()) {
            if (entity instanceof EtcdNode) {
                EtcdNode node = (EtcdNode) entity;
                addresses.put(node.getNodeName(), getNodeAddress(node));
            }
        }
        sensors().set(ETCD_CLUSTER_NODES, ImmutableMap.copyOf(nodes));
        sensors().set(NODE_LIST, Joiner.on(",").withKeyValueSeparator("=").join(addresses));
    }

    private boolean belongsInServerPool(Entity member) {
        if (member.sensors().get(Attributes.HOSTNAME) == null) {
            LOG.debug("Members of {}, checking {}, eliminating because hostname not yet set", this, member);
            return false;
        }
        if (!getMembers().contains(member)) {
            LOG.debug("Members of {}, checking {}, eliminating because not member", this, member);
            return false;
        }
        LOG.debug("Members of {}, checking {}, approving", this, member);
        return true;
    }

    private String getNodeAddress(EtcdNode node) {
        // TODO Implement EtcdNode.ADVERTISE_PEER_URLS sensor + config on the node instead
        return node.getPeerProtocol() + "://" + node.sensors().get(Attributes.SUBNET_ADDRESS) + ":" + node.getPeerPort();
    }

    public static class MemberTrackingPolicy extends AbstractMembershipTrackingPolicy {
        @Override
        protected void onEntityChange(Entity member) {
            synchronized (member) {
                ((EtcdClusterImpl) entity).onServerPoolMemberChanged(member);
            }
        }

        @Override
        protected void onEntityRemoved(Entity member) {
            synchronized (member) {
                ((EtcdClusterImpl) entity).onServerPoolMemberRemoved(member);
            }
        }
    }
}
