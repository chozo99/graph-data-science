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
package org.neo4j.gds.ml.nodemodels.pipeline.predict;

import org.neo4j.gds.AlgorithmFactory;
import org.neo4j.gds.MutatePropertyProc;
import org.neo4j.gds.api.nodeproperties.DoubleArrayNodeProperties;
import org.neo4j.gds.core.CypherMapWrapper;
import org.neo4j.gds.core.model.ModelCatalog;
import org.neo4j.gds.core.write.NodeProperty;
import org.neo4j.gds.ml.nodemodels.logisticregression.NodeClassificationResult;
import org.neo4j.gds.result.AbstractResultBuilder;
import org.neo4j.gds.results.StandardMutateResult;
import org.neo4j.gds.validation.ValidationConfiguration;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Mode;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.neo4j.gds.ml.nodemodels.pipeline.NodeClassificationPipelineCompanion.PREDICT_DESCRIPTION;

public class NodeClassificationPipelineMutateProc
    extends MutatePropertyProc<
    NodeClassificationPredictPipelineExecutor,
    NodeClassificationResult,
    NodeClassificationPipelineMutateProc.MutateResult,
    NodeClassificationPredictPipelineMutateConfig>
{
    @Context
    public ModelCatalog modelCatalog;

    @Procedure(name = "gds.alpha.ml.pipeline.nodeClassification.predict.mutate", mode = Mode.READ)
    @Description(PREDICT_DESCRIPTION)
    public Stream<MutateResult> mutate(
        @Name(value = "graphName") String graphName,
        @Name(value = "configuration", defaultValue = "{}") Map<String, Object> configuration
    ) {
        return mutate(compute(graphName, configuration));
    }

    @Override
    public ValidationConfiguration<NodeClassificationPredictPipelineMutateConfig> getValidationConfig() {
        return NodeClassificationPipelineCompanion.getValidationConfig(modelCatalog);
    }

    @Override
    protected List<NodeProperty> nodePropertyList(ComputationResult<NodeClassificationPredictPipelineExecutor, NodeClassificationResult, NodeClassificationPredictPipelineMutateConfig> computationResult) {
        var config = computationResult.config();
        var mutateProperty = config.mutateProperty();
        var result = computationResult.result();
        var classProperties = result.predictedClasses().asNodeProperties();
        var nodeProperties = new ArrayList<NodeProperty>();
        nodeProperties.add(NodeProperty.of(mutateProperty, classProperties));

        result.predictedProbabilities().ifPresent((probabilityProperties) -> {
            var properties = new DoubleArrayNodeProperties() {
                @Override
                public long size() {
                    return computationResult.graph().nodeCount();
                }

                @Override
                public double[] doubleArrayValue(long nodeId) {
                    return probabilityProperties.get(nodeId);
                }
            };

            nodeProperties.add(NodeProperty.of(
                config.predictedProbabilityProperty().orElseThrow(),
                properties
            ));
        });

        return nodeProperties;
    }


    @Override
    protected AbstractResultBuilder<MutateResult> resultBuilder(ComputationResult<NodeClassificationPredictPipelineExecutor, NodeClassificationResult, NodeClassificationPredictPipelineMutateConfig> computeResult) {
        return new MutateResult.Builder();
    }

    @Override
    protected NodeClassificationPredictPipelineMutateConfig newConfig(
        String username,
        Optional<String> graphName,
        CypherMapWrapper config
    ) {
        return NodeClassificationPredictPipelineMutateConfig.of(username, graphName, config);
    }

    @Override
    protected AlgorithmFactory<NodeClassificationPredictPipelineExecutor, NodeClassificationPredictPipelineMutateConfig> algorithmFactory() {
        return new NodeClassificationPredictPipelineAlgorithmFactory<>(modelCatalog, this, databaseId());
    }

    @SuppressWarnings("unused")
    public static final class MutateResult extends StandardMutateResult {

        public final long nodePropertiesWritten;

        MutateResult(
            long createMillis,
            long computeMillis,
            long mutateMillis,
            long nodePropertiesWritten,
            Map<String, Object> configuration
        ) {
            super(
                createMillis,
                computeMillis,
                0L,
                mutateMillis,
                configuration
            );
            this.nodePropertiesWritten = nodePropertiesWritten;
        }

        static class Builder extends AbstractResultBuilder<MutateResult> {

            @Override
            public MutateResult build() {
                return new MutateResult(
                    createMillis,
                    computeMillis,
                    mutateMillis,
                    nodePropertiesWritten,
                    config.toMap()
                );
            }
        }
    }
}
