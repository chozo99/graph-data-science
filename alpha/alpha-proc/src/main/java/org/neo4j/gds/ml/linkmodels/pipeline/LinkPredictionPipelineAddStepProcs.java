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
package org.neo4j.gds.ml.linkmodels.pipeline;

import org.neo4j.gds.BaseProc;
import org.neo4j.gds.core.CypherMapWrapper;
import org.neo4j.gds.core.model.ModelCatalog;
import org.neo4j.gds.ml.linkmodels.pipeline.linkFeatures.LinkFeatureStepFactory;
import org.neo4j.gds.ml.linkmodels.pipeline.linkFeatures.linkfunctions.LinkFeatureStepConfigurationImpl;
import org.neo4j.procedure.Context;
import org.neo4j.procedure.Description;
import org.neo4j.procedure.Name;
import org.neo4j.procedure.Procedure;

import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.neo4j.gds.config.RelationshipWeightConfig.RELATIONSHIP_WEIGHT_PROPERTY;
import static org.neo4j.gds.ml.linkmodels.pipeline.LinkPredictionPipelineCompanion.getLPPipeline;
import static org.neo4j.gds.ml.pipeline.NodePropertyStepFactory.createNodePropertyStep;
import static org.neo4j.gds.utils.StringFormatting.formatWithLocale;
import static org.neo4j.procedure.Mode.READ;

public class LinkPredictionPipelineAddStepProcs extends BaseProc {

    @Context
    public ModelCatalog modelCatalog;

    @Procedure(name = "gds.alpha.ml.pipeline.linkPrediction.addNodeProperty", mode = READ)
    @Description("Add a node property step to an existing link prediction pipeline.")
    public Stream<PipelineInfoResult> addNodeProperty(
        @Name("pipelineName") String pipelineName,
        @Name("procedureName") String taskName,
        @Name("procedureConfiguration") Map<String, Object> procedureConfig
    ) {
        var pipeline = getLPPipeline(modelCatalog, pipelineName, username());
        validateRelationshipProperty(pipeline, procedureConfig);

        pipeline.addNodePropertyStep(createNodePropertyStep(this, taskName, procedureConfig));

        return Stream.of(new PipelineInfoResult(pipelineName, pipeline));
    }

    @Procedure(name = "gds.alpha.ml.pipeline.linkPrediction.addFeature", mode = READ)
    @Description("Add a feature step to an existing link prediction pipeline.")
    public Stream<PipelineInfoResult> addFeature(
        @Name("pipelineName") String pipelineName,
        @Name("featureType") String featureType,
        @Name("configuration") Map<String, Object> config
    ) {
        var pipeline = getLPPipeline(modelCatalog, pipelineName, username());

        var parsedConfig = new LinkFeatureStepConfigurationImpl(CypherMapWrapper.create(config));

        pipeline.addFeatureStep(LinkFeatureStepFactory.create(featureType, parsedConfig));

        return Stream.of(new PipelineInfoResult(pipelineName, pipeline));
    }

    // check if adding would result in more than one relationshipWeightProperty
    private void validateRelationshipProperty(
        LinkPredictionPipeline pipeline,
        Map<String, Object> procedureConfig
    ) {
        if (!procedureConfig.containsKey(RELATIONSHIP_WEIGHT_PROPERTY)) return;
        var maybeRelationshipProperty = pipeline.relationshipWeightProperty();
        if (maybeRelationshipProperty.isEmpty()) return;
        var relationshipProperty = maybeRelationshipProperty.get();
        var property = (String) procedureConfig.get(RELATIONSHIP_WEIGHT_PROPERTY);
        if (relationshipProperty.equals(property)) return;

        String tasks = pipeline.tasksByRelationshipProperty()
            .get(relationshipProperty)
            .stream()
            .map(s -> "`" + s + "`")
            .collect(Collectors.joining(", "));
        throw new IllegalArgumentException(formatWithLocale(
            "Node property steps added to a pipeline may not have different non-null values for `%s`. " +
            "Pipeline already contains tasks %s which use the value `%s`.",
            RELATIONSHIP_WEIGHT_PROPERTY,
            tasks,
            relationshipProperty
        ));
    }
}
