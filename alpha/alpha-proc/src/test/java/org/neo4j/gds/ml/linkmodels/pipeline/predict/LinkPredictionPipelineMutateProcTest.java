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
package org.neo4j.gds.ml.linkmodels.pipeline.predict;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.neo4j.gds.AlgoBaseProc;
import org.neo4j.gds.GdsCypher;
import org.neo4j.gds.Orientation;
import org.neo4j.gds.RelationshipType;
import org.neo4j.gds.api.Graph;
import org.neo4j.gds.core.loading.GraphStoreCatalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.isA;
import static org.hamcrest.number.OrderingComparison.greaterThan;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.neo4j.gds.TestSupport.assertGraphEquals;
import static org.neo4j.gds.TestSupport.fromGdl;

class LinkPredictionPipelineMutateProcTest extends LinkPredictionPipelineProcTestBase {

    @Override
    Class<? extends AlgoBaseProc<?, ?, ?>> getProcedureClazz() {
        return LinkPredictionPipelineMutateProc.class;
    }

    @ParameterizedTest
    @CsvSource(value = {"3, 1", "3, 4", "50, 1", "50, 4"})
    void shouldPredictWithTopN(int topN, int concurrency) {
        runQuery(
            "CALL gds.alpha.ml.pipeline.linkPrediction.predict.mutate('g', {" +
            " modelName: 'model'," +
            " mutateRelationshipType: 'PREDICTED'," +
            " threshold: 0," +
            " topN: $topN," +
            " concurrency:" +
            " $concurrency" +
            "})",
            Map.of("topN", topN, "concurrency", concurrency)
        );

        Graph actualGraph = GraphStoreCatalog.get(getUsername(), db.databaseId(), "g").graphStore().getGraph(
            RelationshipType.of("PREDICTED"), Optional.of("probability"));

        var relationshipRowsGdl = List.of(
            "(n0)-[:PREDICTED {probability: 0.49750002083312506}]->(n4)",
            "(n4)-[:PREDICTED {probability: 0.49750002083312506}]->(n0)",
            "(n1)-[:PREDICTED {probability: 0.11815697780926958}]->(n4)",
            "(n4)-[:PREDICTED {probability: 0.11815697780926958}]->(n1)",
            "(n0)-[:PREDICTED {probability: 0.11506673204554983}]->(n1)",
            "(n1)-[:PREDICTED {probability: 0.11506673204554983}]->(n0)",
            "(n0)-[:PREDICTED {probability: 0.0024726231566347774}]->(n3)",
            "(n3)-[:PREDICTED {probability: 0.0024726231566347774}]->(n0)",
            "(n0)-[:PREDICTED {probability: 0.00020547103309367397}]->(n2)",
            "(n2)-[:PREDICTED {probability: 0.00020547103309367397}]->(n0)",
            "(n2)-[:PREDICTED {probability: 0.000000002810228605019867}]->(n3)",
            "(n3)-[:PREDICTED {probability: 0.000000002810228605019867}]->(n2)"
           // 2 * because result graph is undirected
        ).subList(0, 2 * Math.min(topN, 6));
        var relationshipGdl = String.join(",", relationshipRowsGdl);

        assertGraphEquals(
            fromGdl(
                "  (n0:N {a: 1.0, b: 0.8, c: 1.0})" +
                ", (n1:N {a: 2.0, b: 1.0, c: 1.0})" +
                ", (n2:N {a: 3.0, b: 1.5, c: 1.0})" +
                ", (n3:N {a: 0.0, b: 2.8, c: 1.0})" +
                ", (n4:N {a: 1.0, b: 0.9, c: 1.0})" + relationshipGdl
            ), actualGraph);
    }

    @Test
    void checkYieldsAndMutatedTypeAndProperty() {
        var graphStore = GraphStoreCatalog
            .get(getUsername(), db.databaseId(), "g")
            .graphStore();

        var query = GdsCypher
            .call("g")
            .algo("gds.alpha.ml.pipeline.linkPrediction.predict")
            .mutateMode()
            .addParameter("mutateRelationshipType", "PREDICTED")
            .addParameter("modelName", "model")
            .addParameter("threshold", 0.0)
            .addParameter("topN", 6)
            .yields();

        assertCypherResult(query, List.of(Map.of(
            "createMillis", greaterThan(-1L),
            "computeMillis", greaterThan(-1L),
            "mutateMillis", greaterThan(-1L),
            "postProcessingMillis", 0L,
            // we are writing undirected rels so we get 2x topN
            "relationshipsWritten", 12L,
            "configuration", isA(Map.class),
            "probabilityDistribution", allOf(
                hasKey("min"),
                hasKey("max"),
                hasKey("mean"),
                hasKey("stdDev"),
                hasKey("p1"),
                hasKey("p5"),
                hasKey("p10"),
                hasKey("p25"),
                hasKey("p50"),
                hasKey("p75"),
                hasKey("p90"),
                hasKey("p95"),
                hasKey("p99"),
                hasKey("p100")
            ),
            "samplingStats", Map.of(
                "strategy", "exhaustive",
                "linksConsidered", 6L
            )
        )));

        assertTrue(graphStore.hasRelationshipProperty(RelationshipType.of("PREDICTED"), "probability"));
    }

    @Test
    void requiresUndirectedGraph() {
        runQuery(createQuery("g2", Orientation.NATURAL));

        var query = GdsCypher
            .call("g2")
            .algo("gds.alpha.ml.pipeline.linkPrediction.predict")
            .mutateMode()
            .addParameter("mutateRelationshipType", "PREDICTED")
            .addParameter("modelName", "model")
            .addParameter("threshold", 0.5)
            .addParameter("topN", 9)
            .yields();

        assertError(query, "Procedure requires relationship projections to be UNDIRECTED.");
    }
}
