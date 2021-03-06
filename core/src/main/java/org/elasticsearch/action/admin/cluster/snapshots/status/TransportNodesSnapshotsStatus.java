/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.elasticsearch.action.admin.cluster.snapshots.status;

import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.action.FailedNodeException;
import org.elasticsearch.action.support.ActionFilters;
import org.elasticsearch.action.support.nodes.BaseNodeRequest;
import org.elasticsearch.action.support.nodes.BaseNodeResponse;
import org.elasticsearch.action.support.nodes.BaseNodesRequest;
import org.elasticsearch.action.support.nodes.BaseNodesResponse;
import org.elasticsearch.action.support.nodes.TransportNodesAction;
import org.elasticsearch.cluster.ClusterName;
import org.elasticsearch.cluster.metadata.IndexNameExpressionResolver;
import org.elasticsearch.cluster.metadata.SnapshotId;
import org.elasticsearch.cluster.node.DiscoveryNode;
import org.elasticsearch.cluster.service.ClusterService;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.shard.ShardId;
import org.elasticsearch.index.snapshots.IndexShardSnapshotStatus;
import org.elasticsearch.snapshots.SnapshotShardsService;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportService;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Collections.unmodifiableMap;

/**
 * Transport client that collects snapshot shard statuses from data nodes
 */
public class TransportNodesSnapshotsStatus extends TransportNodesAction<TransportNodesSnapshotsStatus.Request,
                                                                        TransportNodesSnapshotsStatus.NodesSnapshotStatus,
                                                                        TransportNodesSnapshotsStatus.NodeRequest,
                                                                        TransportNodesSnapshotsStatus.NodeSnapshotStatus> {

    public static final String ACTION_NAME = SnapshotsStatusAction.NAME + "[nodes]";

    private final SnapshotShardsService snapshotShardsService;

    @Inject
    public TransportNodesSnapshotsStatus(Settings settings, ClusterName clusterName, ThreadPool threadPool,
                                         ClusterService clusterService, TransportService transportService,
                                         SnapshotShardsService snapshotShardsService, ActionFilters actionFilters,
                                         IndexNameExpressionResolver indexNameExpressionResolver) {
        super(settings, ACTION_NAME, clusterName, threadPool, clusterService, transportService, actionFilters, indexNameExpressionResolver,
              Request::new, NodeRequest::new, ThreadPool.Names.GENERIC, NodeSnapshotStatus.class);
        this.snapshotShardsService = snapshotShardsService;
    }

    @Override
    protected boolean transportCompress() {
        return true; // compress since the metadata can become large
    }

    @Override
    protected NodeRequest newNodeRequest(String nodeId, Request request) {
        return new NodeRequest(nodeId, request);
    }

    @Override
    protected NodeSnapshotStatus newNodeResponse() {
        return new NodeSnapshotStatus();
    }

    @Override
    protected NodesSnapshotStatus newResponse(Request request, List<NodeSnapshotStatus> responses, List<FailedNodeException> failures) {
        return new NodesSnapshotStatus(clusterName, responses, failures);
    }

    @Override
    protected NodeSnapshotStatus nodeOperation(NodeRequest request) {
        Map<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> snapshotMapBuilder = new HashMap<>();
        try {
            String nodeId = clusterService.localNode().getId();
            for (SnapshotId snapshotId : request.snapshotIds) {
                Map<ShardId, IndexShardSnapshotStatus> shardsStatus = snapshotShardsService.currentSnapshotShards(snapshotId);
                if (shardsStatus == null) {
                    continue;
                }
                Map<ShardId, SnapshotIndexShardStatus> shardMapBuilder = new HashMap<>();
                for (Map.Entry<ShardId, IndexShardSnapshotStatus> shardEntry : shardsStatus.entrySet()) {
                    SnapshotIndexShardStatus shardStatus;
                    IndexShardSnapshotStatus.Stage stage = shardEntry.getValue().stage();
                    if (stage != IndexShardSnapshotStatus.Stage.DONE && stage != IndexShardSnapshotStatus.Stage.FAILURE) {
                        // Store node id for the snapshots that are currently running.
                        shardStatus = new SnapshotIndexShardStatus(shardEntry.getKey(), shardEntry.getValue(), nodeId);
                    } else {
                        shardStatus = new SnapshotIndexShardStatus(shardEntry.getKey(), shardEntry.getValue());
                    }
                    shardMapBuilder.put(shardEntry.getKey(), shardStatus);
                }
                snapshotMapBuilder.put(snapshotId, unmodifiableMap(shardMapBuilder));
            }
            return new NodeSnapshotStatus(clusterService.localNode(), unmodifiableMap(snapshotMapBuilder));
        } catch (Exception e) {
            throw new ElasticsearchException("failed to load metadata", e);
        }
    }

    @Override
    protected boolean accumulateExceptions() {
        return true;
    }

    public static class Request extends BaseNodesRequest<Request> {

        private SnapshotId[] snapshotIds;

        public Request() {
        }

        public Request(String[] nodesIds) {
            super(nodesIds);
        }

        public Request snapshotIds(SnapshotId[] snapshotIds) {
            this.snapshotIds = snapshotIds;
            return this;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            // This operation is never executed remotely
            throw new UnsupportedOperationException("shouldn't be here");
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            // This operation is never executed remotely
            throw new UnsupportedOperationException("shouldn't be here");
        }
    }

    public static class NodesSnapshotStatus extends BaseNodesResponse<NodeSnapshotStatus> {

        NodesSnapshotStatus() {
        }

        public NodesSnapshotStatus(ClusterName clusterName, List<NodeSnapshotStatus> nodes, List<FailedNodeException> failures) {
            super(clusterName, nodes, failures);
        }

        @Override
        protected List<NodeSnapshotStatus> readNodesFrom(StreamInput in) throws IOException {
            return in.readStreamableList(NodeSnapshotStatus::new);
        }

        @Override
        protected void writeNodesTo(StreamOutput out, List<NodeSnapshotStatus> nodes) throws IOException {
            out.writeStreamableList(nodes);
        }
    }


    public static class NodeRequest extends BaseNodeRequest {

        private List<SnapshotId> snapshotIds;

        public NodeRequest() {
        }

        NodeRequest(String nodeId, TransportNodesSnapshotsStatus.Request request) {
            super(nodeId);
            snapshotIds = Arrays.asList(request.snapshotIds);
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            snapshotIds = in.readList(SnapshotId::readSnapshotId);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            out.writeStreamableList(snapshotIds);
        }
    }

    public static class NodeSnapshotStatus extends BaseNodeResponse {

        private Map<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> status;

        NodeSnapshotStatus() {
        }

        public NodeSnapshotStatus(DiscoveryNode node, Map<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> status) {
            super(node);
            this.status = status;
        }

        public Map<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> status() {
            return status;
        }

        @Override
        public void readFrom(StreamInput in) throws IOException {
            super.readFrom(in);
            int numberOfSnapshots = in.readVInt();
            Map<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> snapshotMapBuilder = new HashMap<>(numberOfSnapshots);
            for (int i = 0; i < numberOfSnapshots; i++) {
                SnapshotId snapshotId = SnapshotId.readSnapshotId(in);
                int numberOfShards = in.readVInt();
                Map<ShardId, SnapshotIndexShardStatus> shardMapBuilder = new HashMap<>(numberOfShards);
                for (int j = 0; j < numberOfShards; j++) {
                    ShardId shardId =  ShardId.readShardId(in);
                    SnapshotIndexShardStatus status = SnapshotIndexShardStatus.readShardSnapshotStatus(in);
                    shardMapBuilder.put(shardId, status);
                }
                snapshotMapBuilder.put(snapshotId, unmodifiableMap(shardMapBuilder));
            }
            status = unmodifiableMap(snapshotMapBuilder);
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            super.writeTo(out);
            if (status != null) {
                out.writeVInt(status.size());
                for (Map.Entry<SnapshotId, Map<ShardId, SnapshotIndexShardStatus>> entry : status.entrySet()) {
                    entry.getKey().writeTo(out);
                    out.writeVInt(entry.getValue().size());
                    for (Map.Entry<ShardId, SnapshotIndexShardStatus> shardEntry : entry.getValue().entrySet()) {
                        shardEntry.getKey().writeTo(out);
                        shardEntry.getValue().writeTo(out);
                    }
                }
            } else {
                out.writeVInt(0);
            }
        }
    }
}
