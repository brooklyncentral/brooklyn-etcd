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

import static java.lang.String.format;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.brooklyn.api.entity.Entity;
import org.apache.brooklyn.api.location.OsDetails;
import org.apache.brooklyn.core.entity.Attributes;
import org.apache.brooklyn.core.sensor.DependentConfiguration;
import org.apache.brooklyn.entity.group.DynamicCluster;
import org.apache.brooklyn.entity.software.base.AbstractSoftwareProcessSshDriver;
import org.apache.brooklyn.entity.software.base.lifecycle.ScriptHelper;
import org.apache.brooklyn.location.ssh.SshMachineLocation;
import org.apache.brooklyn.util.collections.MutableMap;
import org.apache.brooklyn.util.core.task.DynamicTasks;
import org.apache.brooklyn.util.os.Os;
import org.apache.brooklyn.util.ssh.BashCommands;
import org.apache.brooklyn.util.text.Strings;
import org.apache.commons.lang3.StringUtils;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicates;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;

public class EtcdNodeSshDriver extends AbstractSoftwareProcessSshDriver implements EtcdNodeDriver {

    public EtcdNodeSshDriver(final EtcdNodeImpl entity, final SshMachineLocation machine) {
        super(entity, machine);

        entity.sensors().set(Attributes.LOG_FILE_LOCATION, getLogFileLocation());
    }

    @Override
    public EtcdNodeImpl getEntity() {
        return EtcdNodeImpl.class.cast(super.getEntity());
    }

    @Override
    public Set<Integer> getPortsUsed() {
        return ImmutableSet.<Integer>builder()
                .addAll(super.getPortsUsed())
                .addAll(getPortMap().values())
                .build();
    }

    protected Map<String, Integer> getPortMap() {
        return MutableMap.of("clientPort", getEntity().getClientPort(), "peerPort", getEntity().getPeerPort());
    }

    @Override
    public void install() {
        OsDetails osDetails = getMachine().getMachineDetails().getOsDetails();
        if (!osDetails.isLinux()) {
            throw new IllegalStateException("Machine was not detected as linux: " + getMachine() +
                    " Details: " + getMachine().getMachineDetails().getOsDetails());
        }

        List<String> urls = resolver.getTargets();
        String saveAs = resolver.getFilename();
        List<String> commands = Lists.newArrayList();

        commands.addAll(BashCommands.commandsToDownloadUrlsAs(urls, saveAs));
        commands.add(BashCommands.INSTALL_TAR);
        commands.add(String.format("tar xvzf %s", saveAs));

        newScript(INSTALLING)
                .body.append(commands)
                .failIfBodyEmpty()
                .failOnNonZeroResultCode()
                .execute();
    }

    @Override
    public void customize() {
        newScript(CUSTOMIZING).execute();

        // Set flag to indicate server has been installed
        entity.sensors().set(EtcdNode.ETCD_NODE_INSTALLED, Boolean.TRUE);
    }

    @Override
    public void launch() {
        DynamicTasks.queueIfPossible(DependentConfiguration.attributeWhenReady(entity, EtcdNode.ETCD_NODE_HAS_JOINED_CLUSTER))
                .orSubmitAndBlock(entity)
                .andWaitForSuccess();

        // Set default values for etcd startup command
        boolean clustered = Optional.fromNullable(entity.sensors().get(DynamicCluster.CLUSTER_MEMBER)).or(false);
        boolean first = entity.config().get(EtcdNode.IS_FIRST);
        String state = (first || !clustered) ? "new" : "existing";
        Collection<String> advertisePeerUrls = getAdvertisePeerUrls();
        String nodes;
        if (clustered) {
            Entity cluster = entity.sensors().get(EtcdCluster.CLUSTER);
            nodes = cluster.sensors().get(EtcdCluster.NODE_LIST);
        } else {
            String prefix = getEntity().getNodeName()  + "=";
            nodes = prefix + Joiner.on("," + prefix).join(advertisePeerUrls);
        }

        // Build etcd startup command
        List<String> commands = Lists.newLinkedList();
        commands.add("cd " + getRunDir());
        commands.add(format("%s --listen-client-urls %s --advertise-client-urls %s "
                + "--listen-peer-urls %s --initial-advertise-peer-urls %s "
                + "--initial-cluster-token %s -name %s --initial-cluster-state %s "
                + "--initial-cluster %s "
                + "%s "
                + "> %s 2>&1 < /dev/null &",
                        Os.mergePathsUnix(getExpandedInstallDir(), "etcd"),
                        getListenClientUrls(), Joiner.on(",").join(getAdvertiseClientUrls()),
                        getListenPeerUrls(), Joiner.on(",").join(advertisePeerUrls),
                        getEntity().getClusterToken(), getEntity().getNodeName(), state, nodes,
                        getAdditionalOptions(),
                        getLogFileLocation()));

        newScript(ImmutableMap.of(USE_PID_FILE, true), LAUNCHING)
                .body.append(commands)
                .failOnNonZeroResultCode()
                .execute();
    }

    @Override
    public void stop() {
        leaveCluster(getEntity().getNodeName());

        newScript(ImmutableMap.of(USE_PID_FILE, true), STOPPING).execute();
    }

    @Override
    public boolean isRunning() {
        return newScript(ImmutableMap.of(USE_PID_FILE, true), CHECK_RUNNING).execute() == 0;
    }

    protected String getEtcdCtlCommand() {
        return Os.mergePathsUnix(getExpandedInstallDir(), "etcdctl");
    }

    protected String getLogFileLocation() {
        return Os.mergePathsUnix(getRunDir(), "console.log");
    }

    protected String getAdditionalOptions() {
        return StringUtils.defaultString(entity.config().get(EtcdNode.ADDITIONAL_OPTIONS)).trim();
    }

    /** @deprecated since 2.1.0. Use {@link #getAdvertiseClientUrls()} instead. */
    @Deprecated
    protected String getClientUrl() {
        return String.format("%s://%s:%d", getEntity().getClientProtocol(), getAddress(), getEntity().getClientPort());
    }

    /** @deprecated since 2.1.0. Use {@link #getAdvertisePeerUrls()} instead. */
    @Deprecated
    protected String getPeerUrl() {
        return String.format("%s://%s:%d", getEntity().getPeerProtocol(), getSubnetAddress(), getEntity().getPeerPort());
    }

    protected Collection<String> getAdvertiseClientUrls() {
        return ImmutableList.of(getClientUrl());
    }

    protected Collection<String> getAdvertisePeerUrls() {
        return ImmutableList.of(getPeerUrl());
    }

    protected String getListenClientUrls() {
        return String.format("%s://0.0.0.0:%d", getEntity().getClientProtocol(), getEntity().getClientPort());
    }

    protected String getListenPeerUrls() {
        return String.format("%s://0.0.0.0:%d", getEntity().getPeerProtocol(), getEntity().getPeerPort());
    }

    @Override
    public void joinCluster(String nodeName, String nodeAddress) {
        List<String> commands = Lists.newLinkedList();
        commands.add("cd " + getRunDir());
        commands.add(String.format("%s --peers %s member add %s %s", getEtcdCtlCommand(), getClientUrl(), nodeName, nodeAddress));
        newScript("joinCluster")
                .body.append(commands)
                .failOnNonZeroResultCode()
                .execute();
    }

    @Override
    public void leaveCluster(String nodeName) {
        List<String> listMembersCommands = Lists.newLinkedList();
        listMembersCommands.add("cd " + getRunDir());
        listMembersCommands.add(String.format("%s --peers %s member list", getEtcdCtlCommand(), getClientUrl()));
        ScriptHelper listMembersScript = newScript("listMembers")
                .body.append(listMembersCommands)
                .gatherOutput();
        int result = listMembersScript.execute();
        if (result != 0) {
            log.warn("{}: The 'member list' command on etcd '{}' failed: {}", new Object[] { entity, getClientUrl(), result });;
            log.debug("{}: STDOUT: {}", entity, listMembersScript.getResultStdout());
            log.debug("{}: STDERR: {}", entity, listMembersScript.getResultStderr());
            return; // Do not throw exception as may not be possible during shutdown
        }
        String output = listMembersScript.getResultStdout();
        Iterable<String> found = Iterables.filter(Splitter.on(CharMatcher.anyOf("\r\n")).split(output), Predicates.containsPattern("name="));
        Optional<String> node = Iterables.tryFind(found, Predicates.containsPattern(nodeName));
        if (Iterables.size(found) > 1 && node.isPresent()) {
            String nodeId = Strings.getFirstWord(node.get()).replace(":", "");
            log.debug("{}: Removing etcd node {} with id {} from {}", new Object[] { entity, nodeName, nodeId, getClientUrl() });

            List<String> removeMemberCommands = Lists.newLinkedList();
            removeMemberCommands.add("cd " + getRunDir());
            removeMemberCommands.add(String.format("%s --peers %s member remove %s", getEtcdCtlCommand(), getClientUrl(), nodeId));
            newScript("removeMember")
                    .body.append(removeMemberCommands)
                    .failOnNonZeroResultCode()
                    .execute();
        } else {
            log.warn("{}: {} is not part of an etcd cluster", entity, nodeName);
        }
    }

}
