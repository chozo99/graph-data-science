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
package org.neo4j.gds.similarity.knn;

import org.neo4j.gds.AlgoBaseProc;
import org.neo4j.gds.AlgorithmFactory;
import org.neo4j.gds.StatsProc;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.core.CypherMapWrapper;
import org.neo4j.gds.core.utils.ProgressTimer;
import org.neo4j.gds.result.AbstractResultBuilder;
import org.neo4j.gds.results.MemoryEstimateResult;
import org.neo4j.gds.similarity.SimilarityGraphResult;
import org.neo4j.gds.similarity.SimilarityProc;
import org.neo4j.gds.similarity.SimilarityStatsResult;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

import static org.neo4j.gds.similarity.SimilarityProc.computeHistogram;
import static org.neo4j.gds.similarity.SimilarityProc.shouldComputeHistogram;
import static org.neo4j.gds.similarity.knn.KnnWriteProc.computeToGraph;
import static org.neo4j.procedure.Mode.READ;

public final class KnnStatsProc extends StatsProc<Knn, Knn.Result, KnnStatsProc.Result, KnnStatsConfig> {

    @Procedure(name = "gds.beta.knn.stats", mode = READ)
    @Description(STATS_DESCRIPTION)
    public Stream<Result> stats(
        @Name(value = "graphName") String graphName,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return stats(compute(graphName, configuration));
    }

    @Procedure(value = "gds.beta.knn.stats.estimate", mode = READ)
    @Description(ESTIMATE_DESCRIPTION)
    public Stream<MemoryEstimateResult> estimateStats(
        @Name(value = "graphName") Object graphNameOrConfig,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return computeEstimate(graphNameOrConfig, configuration);
    }

    @Override
    protected KnnStatsConfig newConfig(
        String username,
        Optional<String> graphName,
        CypherMapWrapper config
    ) {
        return KnnStatsConfig.of(graphName, config);
    }

    @Override
    protected AlgorithmFactory<Knn, KnnStatsConfig> algorithmFactory() {
        return new KnnFactory<>();
    }

    @Override
    protected AbstractResultBuilder<Result> resultBuilder(AlgoBaseProc.ComputationResult<Knn, Knn.Result, KnnStatsConfig> computeResult) {
        throw new UnsupportedOperationException("Knn handles result building individually.");
    }

    @Override
    public Stream<Result> stats(AlgoBaseProc.ComputationResult<Knn, Knn.Result, KnnStatsConfig> computationResult) {
        return runWithExceptionLogging("Graph stats failed", () -> {
            KnnStatsConfig config = computationResult.config();

            if (computationResult.isGraphEmpty()) {
                return Stream.of(
                    new Result(
                        computationResult.createMillis(),
                        0,
                        0,
                        0,
                        0,
                        Collections.emptyMap(),
                        true,
                        0,
                        0,
                        config.toMap()
                    )
                );
            }
            Knn algorithm = Objects.requireNonNull(computationResult.algorithm());
            var result = Objects.requireNonNull(computationResult.result());


            var resultBuilder = new Result.Builder()
                .withRanIterations(result.ranIterations())
                .withNodePairsConsidered(result.nodePairsConsidered())
                .withDidConverge(result.didConverge());

            SimilarityProc.resultBuilderWithTimings(resultBuilder, computationResult);

            if (shouldComputeHistogram(callContext)) {
                try (ProgressTimer ignored = resultBuilder.timePostProcessing()) {
                    SimilarityGraphResult similarityGraphResult = computeToGraph(
                        computationResult.graph(),
                        algorithm.nodeCount(),
                        config.concurrency(),
                        result,
                        algorithm.context()
                    );

                    Graph similarityGraph = similarityGraphResult.similarityGraph();

                    resultBuilder
                        .withHistogram(computeHistogram(similarityGraph))
                        .withNodesCompared(similarityGraphResult.comparedNodes())
                        .withRelationshipsWritten(similarityGraph.relationshipCount());
                }
            } else {
                resultBuilder
                    .withNodesCompared(algorithm.nodeCount())
                    .withRelationshipsWritten(result.totalSimilarityPairs());
            }

            return Stream.of(resultBuilder.build());
        });
    }

    public static class Result extends SimilarityStatsResult {
        public final long ranIterations;
        public final boolean didConverge;
        public final long nodePairsConsidered;

        public Result(
            long createMillis,
            long computeMillis,
            long postProcessingMillis,
            long nodesCompared,
            long nodePairs,
            Map<String, Object> similarityDistribution,
            boolean didConverge,
            long ranIterations,
            long nodePairsConsidered,
            Map<String, Object> configuration
        ) {
            super(
                createMillis,
                computeMillis,
                postProcessingMillis,
                nodesCompared,
                nodePairs,
                similarityDistribution,
                configuration
            );

            this.nodePairsConsidered = nodePairsConsidered;
            this.ranIterations = ranIterations;
            this.didConverge = didConverge;
        }

        public static class Builder extends SimilarityProc.SimilarityResultBuilder<Result> {
            private long ranIterations;
            private boolean didConverge;
            private long nodePairsConsidered;

            @Override
            public Result build() {
                return new Result(
                    createMillis,
                    computeMillis,
                    postProcessingMillis,
                    nodesCompared,
                    relationshipsWritten,
                    distribution(),
                    didConverge,
                    ranIterations,
                    nodePairsConsidered,
                    config.toMap()
                );
            }

            public Builder withDidConverge(boolean didConverge) {
                this.didConverge = didConverge;
                return this;
            }

            public Builder withRanIterations(long ranIterations) {
                this.ranIterations = ranIterations;
                return this;
            }

            Builder withNodePairsConsidered(long nodePairsConsidered) {
                this.nodePairsConsidered = nodePairsConsidered;
                return this;
            }
        }
    }
}
