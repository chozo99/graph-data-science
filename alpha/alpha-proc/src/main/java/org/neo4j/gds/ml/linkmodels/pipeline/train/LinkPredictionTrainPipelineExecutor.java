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
package org.neo4j.gds.ml.linkmodels.pipeline.train;

import org.neo4j.gds.BaseProc;
import org.neo4j.gds.RelationshipType;
import org.neo4j.gds.api.GraphStore;
import org.neo4j.gds.core.model.Model;
import org.neo4j.gds.core.utils.progress.tasks.ProgressTracker;
import org.neo4j.gds.ml.linkmodels.pipeline.LinkPredictionModelInfo;
import org.neo4j.gds.ml.linkmodels.pipeline.LinkPredictionPipeline;
import org.neo4j.gds.ml.linkmodels.pipeline.logisticRegression.LinkLogisticRegressionData;
import org.neo4j.gds.ml.pipeline.ImmutableGraphFilter;
import org.neo4j.gds.ml.pipeline.PipelineExecutor;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class LinkPredictionTrainPipelineExecutor extends PipelineExecutor<
    LinkPredictionTrainConfig,
    LinkPredictionPipeline,
    Model<LinkLogisticRegressionData, LinkPredictionTrainConfig, LinkPredictionModelInfo>,
    LinkPredictionTrainPipelineExecutor
> {

    private final RelationshipSplitter relationshipSplitter;

    public LinkPredictionTrainPipelineExecutor(
        LinkPredictionPipeline pipeline,
        LinkPredictionTrainConfig config,
        BaseProc caller,
        GraphStore graphStore,
        String graphName,
        ProgressTracker progressTracker
    ) {
        super(
            pipeline,
            config,
            caller,
            graphStore,
            graphName,
            progressTracker
        );

        this.relationshipSplitter = new RelationshipSplitter(
            graphName,
            pipeline.splitConfig(),
            caller,
            progressTracker
        );
    }

    @Override
    public Map<DatasetSplits, PipelineExecutor.GraphFilter> splitDataset() {
        this.relationshipSplitter.splitRelationships(
            graphStore,
            config.relationshipTypes(),
            config.nodeLabels(),
            config.randomSeed(),
            pipeline.relationshipWeightProperty()
        );

        var splitConfig = pipeline.splitConfig();

        var nodeLabels = config.nodeLabelIdentifiers(graphStore);
        var trainRelationshipTypes = RelationshipType.listOf(splitConfig.trainRelationshipType());
        var testRelationshipTypes = RelationshipType.listOf(splitConfig.testRelationshipType());
        var featureInputRelationshipType = RelationshipType.listOf(splitConfig.featureInputRelationshipType());

        return Map.of(
            DatasetSplits.TRAIN, ImmutableGraphFilter.of(nodeLabels, trainRelationshipTypes),
            DatasetSplits.TEST, ImmutableGraphFilter.of(nodeLabels, testRelationshipTypes),
            DatasetSplits.FEATURE_INPUT, ImmutableGraphFilter.of(nodeLabels, featureInputRelationshipType)
        );
    }

    @Override
    protected Model<LinkLogisticRegressionData, LinkPredictionTrainConfig, LinkPredictionModelInfo> execute(Map<DatasetSplits, GraphFilter> dataSplits) {
        var trainDataSplit = dataSplits.get(DatasetSplits.TRAIN);
        var testDataSplit = dataSplits.get(DatasetSplits.TEST);

        var trainGraph = graphStore.getGraph(
            trainDataSplit.nodeLabels(),
            trainDataSplit.relationshipTypes(),
            Optional.of("label")
        );
        var testGraph = graphStore.getGraph(
            testDataSplit.nodeLabels(),
            testDataSplit.relationshipTypes(),
            Optional.of("label")
        );
        return new LinkPredictionTrain(
            trainGraph,
            testGraph,
            pipeline,
            config,
            progressTracker
        ).compute();
    }

    @Override
    public LinkPredictionTrainPipelineExecutor me() {
        return this;
    }

    private void removeDataSplitRelationships(Map<DatasetSplits, GraphFilter> datasets) {
        datasets.values()
            .stream()
            .flatMap(graphFilter -> graphFilter.relationshipTypes().stream())
            .distinct()
            .collect(Collectors.toList())
            .forEach(graphStore::deleteRelationships);
    }

    @Override
    protected void cleanUpGraphStore(Map<DatasetSplits, GraphFilter> datasets) {
        removeDataSplitRelationships(datasets);
        super.cleanUpGraphStore(datasets);
    }
}
