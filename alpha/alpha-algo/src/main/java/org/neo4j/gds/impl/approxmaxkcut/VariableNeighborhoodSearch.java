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
package org.neo4j.gds.impl.approxmaxkcut;

import org.neo4j.gds.api.Graph;
import org.neo4j.gds.core.utils.AtomicDoubleArray;
import org.neo4j.gds.core.utils.mem.AllocationTracker;
import org.neo4j.gds.core.utils.paged.HugeByteArray;
import org.neo4j.gds.core.utils.progress.tasks.ProgressTracker;
import org.neo4j.gds.impl.approxmaxkcut.config.ApproxMaxKCutConfig;
import org.neo4j.gds.impl.approxmaxkcut.localsearch.LocalSearch;

import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.function.Supplier;

class VariableNeighborhoodSearch {

    private final Graph graph;
    private final SplittableRandom random;
    private final ApproxMaxKCut.Comparator comparator;
    private final ApproxMaxKCutConfig config;
    private final LocalSearch localSearch;
    private final HugeByteArray[] candidateSolutions;
    private final AtomicDoubleArray[] costs;
    private final ProgressTracker progressTracker;
    private HugeByteArray neighborSolution;
    private AtomicLongArray neighborCardinalities;

    VariableNeighborhoodSearch(
        Graph graph,
        SplittableRandom random,
        ApproxMaxKCut.Comparator comparator,
        ApproxMaxKCutConfig config,
        LocalSearch localSearch,
        HugeByteArray[] candidateSolutions,
        AtomicDoubleArray[] costs,
        ProgressTracker progressTracker,
        AllocationTracker allocationTracker
    ) {
        this.graph = graph;
        this.random = random;
        this.comparator = comparator;
        this.config = config;
        this.localSearch = localSearch;
        this.candidateSolutions = candidateSolutions;
        this.costs = costs;
        this.progressTracker = progressTracker;

        this.neighborSolution = HugeByteArray.newArray(graph.nodeCount(), allocationTracker);
        this.neighborCardinalities = new AtomicLongArray(config.k());
    }

    AtomicLongArray compute(int candidateIdx, AtomicLongArray currCardinalities, Supplier<Boolean> running) {
        var bestCandidateSolution = candidateSolutions[candidateIdx];
        var bestCardinalities = currCardinalities;
        var bestCost = costs[candidateIdx];
        var neighborCost = new AtomicDoubleArray(1);
        boolean perturbSuccess = true;
        var order = 0;

        progressTracker.beginSubTask();

        while ((order < config.vnsMaxNeighborhoodOrder()) && running.get()) {
            bestCandidateSolution.copyTo(neighborSolution, graph.nodeCount());
            copyCardinalities(bestCardinalities, neighborCardinalities);

            // Generate a neighboring candidate solution of the current order.
            for (int i = 0; i < order; i++) {
                perturbSuccess = perturbSolution(neighborSolution, neighborCardinalities);
                if (!perturbSuccess) {
                    break;
                }
            }

            localSearch.compute(neighborSolution, neighborCost, neighborCardinalities, running);

            if (comparator.accept(neighborCost.get(0), bestCost.get(0))) {
                var tmpCandidateSolution = bestCandidateSolution;
                bestCandidateSolution = neighborSolution;
                neighborSolution = tmpCandidateSolution;

                var tmpCardinalities = bestCardinalities;
                bestCardinalities = neighborCardinalities;
                neighborCardinalities = tmpCardinalities;

                bestCost.set(0, neighborCost.get(0));

                // Start from scratch with the new candidate.
                order = 0;
            } else {
                order += 1;
            }

            if (!perturbSuccess) {
                // We were not able to perturb this solution further, so let's stop.
                break;
            }
        }

        // If we obtained a better candidate solution from VNS, swap with that with the one we started with.
        if (bestCandidateSolution != candidateSolutions[candidateIdx]) {
            neighborSolution = candidateSolutions[candidateIdx];
            candidateSolutions[candidateIdx] = bestCandidateSolution;
        }

        progressTracker.endSubTask();

        return bestCardinalities;
    }

    private boolean perturbSolution(
        HugeByteArray solution,
        AtomicLongArray cardinalities
    ) {
        final int MAX_RETRIES = 100;
        int retries = 0;

        while (retries < MAX_RETRIES) {
            long nodeToFlip = random.nextLong(0, graph.nodeCount());
            byte currCommunity = solution.get(nodeToFlip);

            if (cardinalities.get(currCommunity) <= config.minCommunitySizes().get(currCommunity)) {
                // Flipping this node would invalidate the solution in terms of min community sizes.
                retries++;
                continue;
            }

            // For `nodeToFlip`, move to a new random community not equal to its current community in
            // `neighboringSolution`.
            byte rndNewCommunity = (byte) ((solution.get(nodeToFlip) + (random.nextInt(config.k() - 1) + 1))
                                           % config.k());

            solution.set(nodeToFlip, rndNewCommunity);
            cardinalities.decrementAndGet(currCommunity);
            cardinalities.incrementAndGet(rndNewCommunity);

            break;
        }

        return retries != MAX_RETRIES;
    }

    private void copyCardinalities(AtomicLongArray source, AtomicLongArray target) {
        assert target.length() >= source.length();

        for (int i = 0; i < source.length(); i++) {
            target.setPlain(i, source.getPlain(i));
        }
    }
}
