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
package org.neo4j.gds.ml.nodemodels.pipeline;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.neo4j.gds.BaseProcTest;
import org.neo4j.gds.api.schema.GraphSchema;
import org.neo4j.gds.core.model.ModelCatalog;
import org.neo4j.gds.extension.Inject;
import org.neo4j.gds.extension.Neo4jModelCatalogExtension;
import org.neo4j.gds.ml.pipeline.PipelineCreateConfig;
import org.neo4j.gds.model.catalog.ModelListProc;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.isA;
import static org.neo4j.gds.compat.MapUtil.map;
import static org.neo4j.gds.ml.nodemodels.pipeline.NodeClassificationPipelineCompanion.DEFAULT_PARAM_CONFIG;
import static org.neo4j.gds.ml.nodemodels.pipeline.NodeClassificationPipelineCompanion.PIPELINE_MODEL_TYPE;

@Neo4jModelCatalogExtension
public class NodeClassificationPipelineCreateTest extends BaseProcTest {

    @Inject
    ModelCatalog modelCatalog;

    @BeforeEach
    void setUp() throws Exception {
        registerProcedures(NodeClassificationPipelineCreateProc.class, ModelListProc.class);
    }

    @Test
    void createPipeline() {
        var result = NodeClassificationPipelineCreate.create(modelCatalog, getUsername(), "myPipeline");
        assertThat(result.name).isEqualTo("myPipeline");
        assertThat(result.nodePropertySteps).isEqualTo(List.of());
        assertThat(result.featureProperties).isEqualTo(List.of());
        assertThat(result.splitConfig).isEqualTo(NodeClassificationPipelineCompanion.DEFAULT_SPLIT_CONFIG);
        assertThat(result.parameterSpace).isEqualTo(DEFAULT_PARAM_CONFIG);

        assertCypherResult(
            "CALL gds.beta.model.list('myPipeline')",
            singletonList(
                map(
                    "modelInfo", map(
                        "modelName", "myPipeline",
                        "modelType", PIPELINE_MODEL_TYPE,
                        "featurePipeline", Map.of(
                            "nodePropertySteps", List.of(),
                            "featureProperties", List.of()
                        ),
                        "splitConfig", NodeClassificationPipelineCompanion.DEFAULT_SPLIT_CONFIG,
                        "trainingParameterSpace", DEFAULT_PARAM_CONFIG
                    ),
                    "trainConfig", PipelineCreateConfig.of(getUsername()).toMap(),
                    "graphSchema", GraphSchema.empty().toMap(),
                    "loaded", true,
                    "stored", false,
                    "creationTime", isA(ZonedDateTime.class),
                    "shared", false
                )
            )
        );
    }

    @Test
    void failOnCreatingPipelineWithExistingName() {
        NodeClassificationPipelineCreate.create(modelCatalog, getUsername(), "myPipeline");
        assertThatThrownBy(() -> NodeClassificationPipelineCreate.create(modelCatalog, getUsername(), "myPipeline"))
            .hasMessageContaining("Model with name `myPipeline` already exists.");
    }

    @Test
    void failOnCreatingPipelineWithInvalidName() {
        assertThatThrownBy(() -> NodeClassificationPipelineCreate.create(modelCatalog, getUsername(), " "))
            .hasMessageContaining("`pipelineName` must not end or begin with whitespace characters, but got ` `.");
    }
}
