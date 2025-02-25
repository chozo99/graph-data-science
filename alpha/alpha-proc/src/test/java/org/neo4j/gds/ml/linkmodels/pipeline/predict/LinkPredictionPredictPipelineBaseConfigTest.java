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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.neo4j.gds.core.CypherMapWrapper;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

class LinkPredictionPredictPipelineBaseConfigTest {

    public static Stream<Arguments> invalidParameterCombinations() {
        return Stream.of(
            Arguments.of(
                Map.of("modelName", "testModel",
                    "sampleRate", 0.5,
                    "threshold", 0.9,
                    "topN", 10,
                    "topK", 10,
                    "deltaThreshold", 0.9
                ),
                "Configuration parameters ['threshold', 'topN'] may only be set if parameter 'sampleRate' is equal to 1."
            ),
            Arguments.of(
                Map.of("modelName", "testModel",
                    "sampleRate", 0.5,
                    "topK", 10,
                    "deltaThreshold", 0.9,
                    "threshold", 0.9
                ),
                "Configuration parameters ['threshold'] may only be set if parameter 'sampleRate' is equal to 1."
            ),
            Arguments.of(
                Map.of("modelName", "testModel",
                    "sampleRate", 1,
                    "topN", 10,
                    "topK", 10
                ),
                "Configuration parameters ['topK'] may only be set if parameter 'sampleRate' is less than 1."
            ),
            Arguments.of(
                Map.of("modelName", "testModel",
                    "sampleRate", 1,
                    "topN", 10,
                    "topK", 10,
                    "deltaThreshold", 0.9,
                    "randomJoins", 10,
                    "maxIterations", 102
                ),
                "Configuration parameters ['deltaThreshold', 'maxIterations', 'randomJoins', 'topK'] " +
                "may only be set if parameter 'sampleRate' is less than 1."
            )
        );
    }

    @Test
    void failOnRandomSeedWithHighConcurrency() {
        assertThatThrownBy(() -> new LinkPredictionPredictPipelineBaseConfigImpl(
            Optional.of("g"),
            "user",
            CypherMapWrapper.create(Map.of("modelName", "testModel", "randomSeed", 42L, "concurrency", 4))
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(
                "Configuration parameter 'randomSeed' may only be set if parameter 'concurrency' is equal to 1, but got 4.");
    }

    @ParameterizedTest
    @MethodSource("invalidParameterCombinations")
    void failOnIllegalParameterCombinations(Map<String, Object> config, String expectedMessage) {
        assertThatThrownBy(() -> new LinkPredictionPredictPipelineBaseConfigImpl(
            Optional.of(" g"),
            "user",
            CypherMapWrapper.create(config)
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining(expectedMessage);
    }

    @Test
    void failOnDeriveKnnConfig() {
        var exhaustiveConfig = new LinkPredictionPredictPipelineBaseConfigImpl(
            Optional.of("g"),
            "user",
            CypherMapWrapper.create(Map.of(
                    "modelName", "testModel",
                    "sampleRate", 1,
                    "topN", 42
                )
            )
        );

        assertThatThrownBy(exhaustiveConfig::approximateConfig)
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Cannot derive approximateConfig when 'sampleRate' is 1.");
    }

    @Test
    void deriveKnnConfig() {
        var approximateConfig = new LinkPredictionPredictPipelineBaseConfigImpl(
            Optional.of("g"),
            "user",
            CypherMapWrapper.create(Map.of(
                    "modelName", "testModel",
                    "sampleRate", 0.4,
                    "maxIterations", 42,
                    "concurrency", 1,
                    "randomSeed", 42L
                )
            )
        ).approximateConfig();
        assertThat(approximateConfig.maxIterations()).isEqualTo(42);
        assertThat(approximateConfig.sampleRate()).isEqualTo(0.4);
        assertThat(approximateConfig.topK()).isEqualTo(10);
        assertThat(approximateConfig.concurrency()).isEqualTo(1);
        assertThat(approximateConfig.perturbationRate()).isEqualTo(0.0);
        assertThat(approximateConfig.randomSeed()).isEqualTo(Optional.of(42L));

    }

    @Test
    void failOnMissingTopN() {
        assertThatThrownBy(() -> new LinkPredictionPredictPipelineBaseConfigImpl(
            Optional.of("g"),
            "user",
            CypherMapWrapper.create(Map.of(
                    "modelName", "testModel",
                    "sampleRate", 1
                )
            )
        ))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("No value specified for the mandatory configuration parameter `topN`");
    }
}
