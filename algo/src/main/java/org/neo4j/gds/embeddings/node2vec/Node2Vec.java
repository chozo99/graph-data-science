/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.gds.embeddings.node2vec;

import org.neo4j.gds.Algorithm;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.core.utils.mem.MemoryEstimation;
import org.neo4j.gds.core.utils.mem.MemoryEstimations;
import org.neo4j.gds.core.utils.paged.HugeObjectArray;
import org.neo4j.gds.core.utils.progress.tasks.ProgressTracker;
import org.neo4j.gds.mem.MemoryUsage;
import org.neo4j.gds.ml.core.tensor.FloatVector;
import org.neo4j.gds.traversal.RandomWalk;

public class Node2Vec extends Algorithm<Node2Vec, HugeObjectArray<FloatVector>> {

    private final Graph graph;
    private final Node2VecBaseConfig config;
    private final AllocationTracker allocationTracker;

    public static MemoryEstimation memoryEstimation(Node2VecBaseConfig config) {
        return MemoryEstimations.builder(Node2Vec.class)
            .perNode("random walks", (nodeCount) -> {
                var numberOfRandomWalks = nodeCount * config.walksPerNode();
                var randomWalkMemoryUsage = MemoryUsage.sizeOfLongArray(config.walkLength());
                return HugeObjectArray.memoryEstimation(numberOfRandomWalks, randomWalkMemoryUsage);
            })
            .add("probability cache", RandomWalkProbabilities.memoryEstimation())
            .add("model", Node2VecModel.memoryEstimation(config))
            .build();
    }

    public Node2Vec(Graph graph, Node2VecBaseConfig config, ProgressTracker progressTracker, AllocationTracker allocationTracker) {
        super(progressTracker);
        this.graph = graph;
        this.config = config;
        this.allocationTracker = allocationTracker;
    }

    @Override
    public HugeObjectArray<FloatVector> compute() {
        progressTracker.beginSubTask("Node2Vec");

        RandomWalk randomWalk = RandomWalk.create(
            graph,
            config,
            allocationTracker,
            progressTracker
        );

        var probabilitiesBuilder = new RandomWalkProbabilities.Builder(
            graph.nodeCount(),
            config.positiveSamplingFactor(),
            config.negativeSamplingExponent(),
            config.concurrency(),
            allocationTracker
        );
        var walks = new CompressedRandomWalks(graph.nodeCount() * config.walksPerNode(), allocationTracker);

        randomWalk.compute().forEach(walk -> {
            probabilitiesBuilder.registerWalk(walk);
            walks.add(walk);
        });

        var node2VecModel = new Node2VecModel(
            graph.nodeCount(),
            config,
            walks,
            probabilitiesBuilder.build(),
            progressTracker,
            allocationTracker
        );

        node2VecModel.train();

        progressTracker.endSubTask("Node2Vec");
        return node2VecModel.getEmbeddings();
    }

    @Override
    public Node2Vec me() {
        return this;
    }

    @Override
    public void release() {

    }
}
