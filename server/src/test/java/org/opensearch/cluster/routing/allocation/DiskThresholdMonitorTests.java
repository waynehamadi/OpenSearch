/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.cluster.routing.allocation;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.cluster.ClusterInfo;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.DiskUsage;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.block.ClusterBlockLevel;
import org.opensearch.cluster.block.ClusterBlocks;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingNode;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRoutingState;
import org.opensearch.common.Priority;
import org.opensearch.common.collect.ImmutableOpenMap;
import org.opensearch.common.logging.Loggers;
import org.opensearch.common.settings.ClusterSettings;
import org.opensearch.common.settings.Settings;
import org.opensearch.index.shard.ShardId;
import org.opensearch.test.MockLogAppender;
import org.opensearch.test.junit.annotations.TestLogging;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.DiskThresholdMonitor;
import org.opensearch.cluster.routing.allocation.DiskThresholdSettings;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;

public class DiskThresholdMonitorTests extends OpenSearchAllocationTestCase {

    public void testMarkFloodStageIndicesReadOnly() {
        AllocationService allocation = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10).build());
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test").settings(settings(Version.CURRENT)
                .put("index.routing.allocation.require._id", "node2")).numberOfShards(1).numberOfReplicas(0))
            .put(IndexMetadata.builder("test_1").settings(settings(Version.CURRENT)
                .put("index.routing.allocation.require._id", "node1")).numberOfShards(1).numberOfReplicas(0))
            .put(IndexMetadata.builder("test_2").settings(settings(Version.CURRENT)
                .put("index.routing.allocation.require._id", "node1")).numberOfShards(1).numberOfReplicas(0))
            .build();
        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test"))
            .addAsNew(metadata.index("test_1"))
            .addAsNew(metadata.index("test_2"))
            .build();
        final ClusterState clusterState = applyStartedShardsUntilNoChange(
            ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
                .metadata(metadata).routingTable(routingTable)
                .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2"))).build(), allocation);
        AtomicBoolean reroute = new AtomicBoolean(false);
        AtomicReference<Set<String>> indices = new AtomicReference<>();
        AtomicLong currentTime = new AtomicLong();
        DiskThresholdMonitor monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> clusterState,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, currentTime::get,
            (reason, priority, listener) -> {
                assertTrue(reroute.compareAndSet(false, true));
                assertThat(priority, equalTo(Priority.HIGH));
                listener.onResponse(null);
            }) {

            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToMarkReadOnly, ActionListener<Void> listener, boolean readOnly) {
                assertTrue(indices.compareAndSet(null, indicesToMarkReadOnly));
                assertTrue(readOnly);
                listener.onResponse(null);
            }
        };

        ImmutableOpenMap.Builder<String, DiskUsage> builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, 4));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, 30));
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertFalse(reroute.get());
        assertEquals(new HashSet<>(Arrays.asList("test_1", "test_2")), indices.get());

        indices.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, 4));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, 5));
        currentTime.addAndGet(randomLongBetween(60001, 120000));
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertTrue(reroute.get());
        assertEquals(new HashSet<>(Arrays.asList("test_1", "test_2")), indices.get());
        IndexMetadata indexMetadata = IndexMetadata.builder(clusterState.metadata().index("test_2")).settings(Settings.builder()
            .put(clusterState.metadata()
            .index("test_2").getSettings())
            .put(IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.getKey(), true)).build();

        // now we mark one index as read-only and assert that we don't mark it as such again
        final ClusterState anotherFinalClusterState = ClusterState.builder(clusterState).metadata(Metadata.builder(clusterState.metadata())
            .put(clusterState.metadata().index("test"), false)
            .put(clusterState.metadata().index("test_1"), false)
            .put(indexMetadata, true).build())
            .blocks(ClusterBlocks.builder().addBlocks(indexMetadata).build()).build();
        assertTrue(anotherFinalClusterState.blocks().indexBlocked(ClusterBlockLevel.WRITE, "test_2"));

        monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> anotherFinalClusterState,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, currentTime::get,
            (reason, priority, listener) -> {
                assertTrue(reroute.compareAndSet(false, true));
                assertThat(priority, equalTo(Priority.HIGH));
                listener.onResponse(null);
            }) {
            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToMarkReadOnly, ActionListener<Void> listener, boolean readOnly) {
                assertTrue(indices.compareAndSet(null, indicesToMarkReadOnly));
                assertTrue(readOnly);
                listener.onResponse(null);
            }
        };

        indices.set(null);
        reroute.set(false);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1","node1", "/foo/bar", 100, 4));
        builder.put("node2", new DiskUsage("node2","node2", "/foo/bar", 100, 5));
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertTrue(reroute.get());
        assertEquals(Collections.singleton("test_1"), indices.get());
    }

    public void testDoesNotSubmitRerouteTaskTooFrequently() {
        final ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2"))).build();
        AtomicLong currentTime = new AtomicLong();
        AtomicReference<ActionListener<ClusterState>> listenerReference = new AtomicReference<>();
        DiskThresholdMonitor monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> clusterState,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, currentTime::get,
            (reason, priority, listener) -> {
                assertNotNull(listener);
                assertThat(priority, equalTo(Priority.HIGH));
                assertTrue(listenerReference.compareAndSet(null, listener));
            }) {
            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToMarkReadOnly, ActionListener<Void> listener, boolean readOnly) {
                throw new AssertionError("unexpected");
            }
        };

        final ImmutableOpenMap.Builder<String, DiskUsage> allDisksOkBuilder;
        allDisksOkBuilder = ImmutableOpenMap.builder();
        allDisksOkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, 50));
        allDisksOkBuilder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, 50));
        final ImmutableOpenMap<String, DiskUsage> allDisksOk = allDisksOkBuilder.build();

        final ImmutableOpenMap.Builder<String, DiskUsage> oneDiskAboveWatermarkBuilder = ImmutableOpenMap.builder();
        oneDiskAboveWatermarkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(5, 9)));
        oneDiskAboveWatermarkBuilder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, 50));
        final ImmutableOpenMap<String, DiskUsage> oneDiskAboveWatermark = oneDiskAboveWatermarkBuilder.build();

        // should not reroute when all disks are ok
        currentTime.addAndGet(randomLongBetween(0, 120000));
        monitor.onNewInfo(clusterInfo(allDisksOk));
        assertNull(listenerReference.get());

        // should reroute when one disk goes over the watermark
        currentTime.addAndGet(randomLongBetween(0, 120000));
        monitor.onNewInfo(clusterInfo(oneDiskAboveWatermark));
        assertNotNull(listenerReference.get());
        listenerReference.getAndSet(null).onResponse(clusterState);

        if (randomBoolean()) {
            // should not re-route again within the reroute interval
            currentTime.addAndGet(randomLongBetween(0,
                DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).millis()));
            monitor.onNewInfo(clusterInfo(allDisksOk));
            assertNull(listenerReference.get());
        }

        // should reroute again when one disk is still over the watermark
        currentTime.addAndGet(randomLongBetween(
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).millis() + 1, 120000));
        monitor.onNewInfo(clusterInfo(oneDiskAboveWatermark));
        assertNotNull(listenerReference.get());
        final ActionListener<ClusterState> rerouteListener1 = listenerReference.getAndSet(null);

        // should not re-route again before reroute has completed
        currentTime.addAndGet(randomLongBetween(0, 120000));
        monitor.onNewInfo(clusterInfo(allDisksOk));
        assertNull(listenerReference.get());

        // complete reroute
        rerouteListener1.onResponse(clusterState);

        if (randomBoolean()) {
            // should not re-route again within the reroute interval
            currentTime.addAndGet(randomLongBetween(0,
                DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).millis()));
            monitor.onNewInfo(clusterInfo(allDisksOk));
            assertNull(listenerReference.get());
        }

        // should reroute again after the reroute interval
        currentTime.addAndGet(randomLongBetween(
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).millis() + 1, 120000));
        monitor.onNewInfo(clusterInfo(allDisksOk));
        assertNotNull(listenerReference.get());
        listenerReference.getAndSet(null).onResponse(null);

        // should not reroute again when it is not required
        currentTime.addAndGet(randomLongBetween(0, 120000));
        monitor.onNewInfo(clusterInfo(allDisksOk));
        assertNull(listenerReference.get());

        // should reroute again when one disk has reserved space that pushes it over the high watermark
        final ImmutableOpenMap.Builder<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace> builder = ImmutableOpenMap.builder(1);
        builder.put(new ClusterInfo.NodeAndPath("node1", "/foo/bar"),
            new ClusterInfo.ReservedSpace.Builder().add(new ShardId("baz", "quux", 0), between(41, 100)).build());
        final ImmutableOpenMap<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace> reservedSpaces = builder.build();

        currentTime.addAndGet(randomLongBetween(
            DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).millis() + 1, 120000));
        monitor.onNewInfo(clusterInfo(allDisksOk, reservedSpaces));
        assertNotNull(listenerReference.get());
        listenerReference.getAndSet(null).onResponse(null);

    }

    public void testAutoReleaseIndices() {
        AtomicReference<Set<String>> indicesToMarkReadOnly = new AtomicReference<>();
        AtomicReference<Set<String>> indicesToRelease = new AtomicReference<>();
        AllocationService allocation = createAllocationService(Settings.builder()
            .put("cluster.routing.allocation.node_concurrent_recoveries", 10).build());
        Metadata metadata = Metadata.builder()
            .put(IndexMetadata.builder("test_1").settings(settings(Version.CURRENT)).numberOfShards(2).numberOfReplicas(1))
            .put(IndexMetadata.builder("test_2").settings(settings(Version.CURRENT)).numberOfShards(2).numberOfReplicas(1))
            .build();
        RoutingTable routingTable = RoutingTable.builder()
            .addAsNew(metadata.index("test_1"))
            .addAsNew(metadata.index("test_2"))
            .build();
        final ClusterState clusterState = applyStartedShardsUntilNoChange(
            ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
                .metadata(metadata).routingTable(routingTable)
                .nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2"))).build(), allocation);
        assertThat(clusterState.getRoutingTable().shardsWithState(ShardRoutingState.STARTED).size(), equalTo(8));

        final ImmutableOpenMap.Builder<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace> reservedSpacesBuilder
            = ImmutableOpenMap.builder();
        final int reservedSpaceNode1 = between(0, 10);
        reservedSpacesBuilder.put(new ClusterInfo.NodeAndPath("node1", "/foo/bar"),
            new ClusterInfo.ReservedSpace.Builder().add(new ShardId("", "", 0), reservedSpaceNode1).build());
        final int reservedSpaceNode2 = between(0, 10);
        reservedSpacesBuilder.put(new ClusterInfo.NodeAndPath("node2", "/foo/bar"),
            new ClusterInfo.ReservedSpace.Builder().add(new ShardId("", "", 0), reservedSpaceNode2).build());
        ImmutableOpenMap<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace> reservedSpaces = reservedSpacesBuilder.build();

        DiskThresholdMonitor monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> clusterState,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, () -> 0L,
            (reason, priority, listener) -> {
                assertNotNull(listener);
                assertThat(priority, equalTo(Priority.HIGH));
                listener.onResponse(clusterState);
            }) {
            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToUpdate, ActionListener<Void> listener, boolean readOnly) {
                if (readOnly) {
                    assertTrue(indicesToMarkReadOnly.compareAndSet(null, indicesToUpdate));
                } else {
                    assertTrue(indicesToRelease.compareAndSet(null, indicesToUpdate));
                }
                listener.onResponse(null);
            }
        };
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        ImmutableOpenMap.Builder<String, DiskUsage> builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(0, 4)));
        builder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, between(0, 4)));
        monitor.onNewInfo(clusterInfo(builder.build(), reservedSpaces));
        assertEquals(new HashSet<>(Arrays.asList("test_1", "test_2")), indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());

        // Reserved space is ignored when applying block
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(5, 90)));
        builder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, between(5, 90)));
        monitor.onNewInfo(clusterInfo(builder.build(), reservedSpaces));
        assertNull(indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());

        // Change cluster state so that "test_2" index is blocked (read only)
        IndexMetadata indexMetadata = IndexMetadata.builder(clusterState.metadata().index("test_2")).settings(Settings.builder()
            .put(clusterState.metadata()
                .index("test_2").getSettings())
            .put(IndexMetadata.INDEX_BLOCKS_READ_ONLY_ALLOW_DELETE_SETTING.getKey(), true)).build();

        ClusterState clusterStateWithBlocks = ClusterState.builder(clusterState).metadata(Metadata.builder(clusterState.metadata())
            .put(indexMetadata, true).build())
            .blocks(ClusterBlocks.builder().addBlocks(indexMetadata).build()).build();

        assertTrue(clusterStateWithBlocks.blocks().indexBlocked(ClusterBlockLevel.WRITE, "test_2"));
        monitor = new DiskThresholdMonitor(Settings.EMPTY, () -> clusterStateWithBlocks,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, () -> 0L,
            (reason, priority, listener) -> {
                assertNotNull(listener);
                assertThat(priority, equalTo(Priority.HIGH));
                listener.onResponse(clusterStateWithBlocks);
            }) {
            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToUpdate, ActionListener<Void> listener, boolean readOnly) {
                if (readOnly) {
                    assertTrue(indicesToMarkReadOnly.compareAndSet(null, indicesToUpdate));
                } else {
                    assertTrue(indicesToRelease.compareAndSet(null, indicesToUpdate));
                }
                listener.onResponse(null);
            }
        };
        // When free disk on any of node1 or node2 goes below 5% flood watermark, then apply index block on indices not having the block
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(0, 100)));
        builder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, between(0, 4)));
        monitor.onNewInfo(clusterInfo(builder.build(), reservedSpaces));
        assertThat(indicesToMarkReadOnly.get(), contains("test_1"));
        assertNull(indicesToRelease.get());

        // When free disk on node1 and node2 goes above 10% high watermark then release index block, ignoring reserved space
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(10, 100)));
        builder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, between(10, 100)));
        monitor.onNewInfo(clusterInfo(builder.build(), reservedSpaces));
        assertNull(indicesToMarkReadOnly.get());
        assertThat(indicesToRelease.get(), contains("test_2"));

        // When no usage information is present for node2, we don't release the block
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(0, 4)));
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertThat(indicesToMarkReadOnly.get(), contains("test_1"));
        assertNull(indicesToRelease.get());

        // When disk usage on one node is between the high and flood-stage watermarks, nothing changes
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(5, 9)));
        builder.put("node2", new DiskUsage("node2", "node2", "/foo/bar", 100, between(5, 100)));
        if (randomBoolean()) {
            builder.put("node3", new DiskUsage("node3", "node3", "/foo/bar", 100, between(0, 100)));
        }
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertNull(indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());

        // When disk usage on one node is missing and the other is below the high watermark, nothing changes
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(5, 100)));
        if (randomBoolean()) {
            builder.put("node3", new DiskUsage("node3", "node3", "/foo/bar", 100, between(0, 100)));
        }
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertNull(indicesToMarkReadOnly.get());
        assertNull(indicesToRelease.get());

        // When disk usage on one node is missing and the other is above the flood-stage watermark, affected indices are blocked
        indicesToMarkReadOnly.set(null);
        indicesToRelease.set(null);
        builder = ImmutableOpenMap.builder();
        builder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(0, 4)));
        if (randomBoolean()) {
            builder.put("node3", new DiskUsage("node3", "node3", "/foo/bar", 100, between(0, 100)));
        }
        monitor.onNewInfo(clusterInfo(builder.build()));
        assertThat(indicesToMarkReadOnly.get(), contains("test_1"));
        assertNull(indicesToRelease.get());
    }

    @TestLogging(value="org.opensearch.cluster.routing.allocation.DiskThresholdMonitor:INFO", reason="testing INFO/WARN logging")
    public void testDiskMonitorLogging() throws IllegalAccessException {
        final ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .nodes(DiscoveryNodes.builder().add(newNode("node1"))).build();
        final AtomicReference<ClusterState> clusterStateRef = new AtomicReference<>(clusterState);
        final AtomicBoolean advanceTime = new AtomicBoolean(randomBoolean());

        final LongSupplier timeSupplier = new LongSupplier() {
            long time;

            @Override
            public long getAsLong() {
                if (advanceTime.get()) {
                    time += DiskThresholdSettings.CLUSTER_ROUTING_ALLOCATION_REROUTE_INTERVAL_SETTING.get(Settings.EMPTY).getMillis() + 1;
                }
                logger.info("time: [{}]", time);
                return time;
            }
        };

        final AtomicLong relocatingShardSizeRef = new AtomicLong();

        DiskThresholdMonitor monitor = new DiskThresholdMonitor(Settings.EMPTY, clusterStateRef::get,
            new ClusterSettings(Settings.EMPTY, ClusterSettings.BUILT_IN_CLUSTER_SETTINGS), null, timeSupplier,
            (reason, priority, listener) -> listener.onResponse(clusterStateRef.get())) {
            @Override
            protected void updateIndicesReadOnly(Set<String> indicesToMarkReadOnly, ActionListener<Void> listener, boolean readOnly) {
                listener.onResponse(null);
            }

            @Override
            long sizeOfRelocatingShards(RoutingNode routingNode, DiskUsage diskUsage, ClusterInfo info, ClusterState reroutedClusterState) {
                return relocatingShardSizeRef.get();
            }
        };

        final ImmutableOpenMap.Builder<String, DiskUsage> allDisksOkBuilder;
        allDisksOkBuilder = ImmutableOpenMap.builder();
        allDisksOkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(15, 100)));
        final ImmutableOpenMap<String, DiskUsage> allDisksOk = allDisksOkBuilder.build();

        final ImmutableOpenMap.Builder<String, DiskUsage> aboveLowWatermarkBuilder = ImmutableOpenMap.builder();
        aboveLowWatermarkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(10, 14)));
        final ImmutableOpenMap<String, DiskUsage> aboveLowWatermark = aboveLowWatermarkBuilder.build();

        final ImmutableOpenMap.Builder<String, DiskUsage> aboveHighWatermarkBuilder = ImmutableOpenMap.builder();
        aboveHighWatermarkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(5, 9)));
        final ImmutableOpenMap<String, DiskUsage> aboveHighWatermark = aboveHighWatermarkBuilder.build();

        final ImmutableOpenMap.Builder<String, DiskUsage> aboveFloodStageWatermarkBuilder = ImmutableOpenMap.builder();
        aboveFloodStageWatermarkBuilder.put("node1", new DiskUsage("node1", "node1", "/foo/bar", 100, between(0, 4)));
        final ImmutableOpenMap<String, DiskUsage> aboveFloodStageWatermark = aboveFloodStageWatermarkBuilder.build();

        assertNoLogging(monitor, allDisksOk);

        assertSingleInfoMessage(monitor, aboveLowWatermark,
            "low disk watermark [85%] exceeded on * replicas will not be assigned to this node");

        advanceTime.set(false); // will do one reroute and emit warnings, but subsequent reroutes and associated messages are delayed
        assertSingleWarningMessage(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to continue to exceed the high disk watermark when these relocations are complete");

        advanceTime.set(true);
        assertRepeatedWarningMessages(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to continue to exceed the high disk watermark when these relocations are complete");

        advanceTime.set(randomBoolean());
        assertRepeatedWarningMessages(monitor, aboveFloodStageWatermark,
            "flood stage disk watermark [95%] exceeded on * all indices on this node will be marked read-only");

        relocatingShardSizeRef.set(-5L);
        advanceTime.set(true);
        assertSingleInfoMessage(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to be below the high disk watermark when these relocations are complete");

        relocatingShardSizeRef.set(0L);
        timeSupplier.getAsLong(); // advance time long enough to do another reroute
        advanceTime.set(false); // will do one reroute and emit warnings, but subsequent reroutes and associated messages are delayed
        assertSingleWarningMessage(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to continue to exceed the high disk watermark when these relocations are complete");

        advanceTime.set(true);
        assertRepeatedWarningMessages(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to continue to exceed the high disk watermark when these relocations are complete");

        advanceTime.set(randomBoolean());
        assertSingleInfoMessage(monitor, aboveLowWatermark,
            "high disk watermark [90%] no longer exceeded on * but low disk watermark [85%] is still exceeded");

        advanceTime.set(true); // only log about dropping below the low disk watermark on a reroute
        assertSingleInfoMessage(monitor, allDisksOk,
            "low disk watermark [85%] no longer exceeded on *");

        advanceTime.set(randomBoolean());
        assertRepeatedWarningMessages(monitor, aboveFloodStageWatermark,
            "flood stage disk watermark [95%] exceeded on * all indices on this node will be marked read-only");

        assertSingleInfoMessage(monitor, allDisksOk,
            "low disk watermark [85%] no longer exceeded on *");

        advanceTime.set(true);
        assertRepeatedWarningMessages(monitor, aboveHighWatermark,
            "high disk watermark [90%] exceeded on * shards will be relocated away from this node* " +
                "the node is expected to continue to exceed the high disk watermark when these relocations are complete");

        assertSingleInfoMessage(monitor, allDisksOk,
            "low disk watermark [85%] no longer exceeded on *");

        assertRepeatedWarningMessages(monitor, aboveFloodStageWatermark,
            "flood stage disk watermark [95%] exceeded on * all indices on this node will be marked read-only");

        assertSingleInfoMessage(monitor, aboveLowWatermark,
            "high disk watermark [90%] no longer exceeded on * but low disk watermark [85%] is still exceeded");
    }

    private void assertNoLogging(DiskThresholdMonitor monitor,
                                 ImmutableOpenMap<String, DiskUsage> diskUsages) throws IllegalAccessException {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.start();
        mockAppender.addExpectation(new MockLogAppender.UnseenEventExpectation(
            "any INFO message",
            DiskThresholdMonitor.class.getCanonicalName(),
            Level.INFO,
            "*"));
        mockAppender.addExpectation(new MockLogAppender.UnseenEventExpectation(
            "any WARN message",
            DiskThresholdMonitor.class.getCanonicalName(),
            Level.WARN,
            "*"));

        Logger diskThresholdMonitorLogger = LogManager.getLogger(DiskThresholdMonitor.class);
        Loggers.addAppender(diskThresholdMonitorLogger, mockAppender);

        for (int i = between(1, 3); i >= 0; i--) {
            monitor.onNewInfo(clusterInfo(diskUsages));
        }

        mockAppender.assertAllExpectationsMatched();
        Loggers.removeAppender(diskThresholdMonitorLogger, mockAppender);
        mockAppender.stop();
    }

    private void assertRepeatedWarningMessages(DiskThresholdMonitor monitor,
                                               ImmutableOpenMap<String, DiskUsage> diskUsages,
                                               String message) throws IllegalAccessException {
        for (int i = between(1, 3); i >= 0; i--) {
            assertLogging(monitor, diskUsages, Level.WARN, message);
        }
    }

    private void assertSingleWarningMessage(DiskThresholdMonitor monitor,
                                            ImmutableOpenMap<String, DiskUsage> diskUsages,
                                            String message) throws IllegalAccessException {
        assertLogging(monitor, diskUsages, Level.WARN, message);
        assertNoLogging(monitor, diskUsages);
    }

    private void assertSingleInfoMessage(DiskThresholdMonitor monitor,
                                         ImmutableOpenMap<String, DiskUsage> diskUsages,
                                         String message) throws IllegalAccessException {
        assertLogging(monitor, diskUsages, Level.INFO, message);
        assertNoLogging(monitor, diskUsages);
    }

    private void assertLogging(DiskThresholdMonitor monitor,
                               ImmutableOpenMap<String, DiskUsage> diskUsages,
                               Level level,
                               String message) throws IllegalAccessException {
        MockLogAppender mockAppender = new MockLogAppender();
        mockAppender.start();
        mockAppender.addExpectation(new MockLogAppender.SeenEventExpectation(
            "expected message",
            DiskThresholdMonitor.class.getCanonicalName(),
            level,
            message));
        mockAppender.addExpectation(new MockLogAppender.UnseenEventExpectation(
            "any message of another level",
            DiskThresholdMonitor.class.getCanonicalName(),
            level == Level.INFO ? Level.WARN : Level.INFO,
            "*"));

        Logger diskThresholdMonitorLogger = LogManager.getLogger(DiskThresholdMonitor.class);
        Loggers.addAppender(diskThresholdMonitorLogger, mockAppender);

        monitor.onNewInfo(clusterInfo(diskUsages));

        mockAppender.assertAllExpectationsMatched();
        Loggers.removeAppender(diskThresholdMonitorLogger, mockAppender);
        mockAppender.stop();
    }

    private static ClusterInfo clusterInfo(ImmutableOpenMap<String, DiskUsage> diskUsages) {
        return clusterInfo(diskUsages, ImmutableOpenMap.of());
    }

    private static ClusterInfo clusterInfo(ImmutableOpenMap<String, DiskUsage> diskUsages,
                                           ImmutableOpenMap<ClusterInfo.NodeAndPath, ClusterInfo.ReservedSpace> reservedSpace) {
        return new ClusterInfo(diskUsages, null, null, null, reservedSpace);
    }

}
