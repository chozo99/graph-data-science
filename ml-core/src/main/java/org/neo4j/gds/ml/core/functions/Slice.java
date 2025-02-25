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
package org.neo4j.gds.ml.core.functions;

import org.neo4j.gds.ml.core.ComputationContext;
import org.neo4j.gds.ml.core.Dimensions;
import org.neo4j.gds.ml.core.Variable;
import org.neo4j.gds.ml.core.tensor.Matrix;
import org.neo4j.gds.ml.core.tensor.Tensor;

public class Slice extends SingleParentVariable<Matrix> {

    private final int[] batchIds;
    private final int rows;
    private final int cols;

    public Slice(Variable<Matrix> parent, int[] batchIds) {
        super(parent, Dimensions.matrix(batchIds.length, parent.dimension(1)));

        this.batchIds = batchIds;
        this.rows = batchIds.length;
        this.cols = parent.dimension(1);
    }

    @Override
    public Matrix apply(ComputationContext ctx) {
        double[] parentData = ctx.data(parent()).data();

        double[] result = new double[rows * cols];

        for (int row = 0; row < rows; row++) {
            System.arraycopy(parentData, batchIds[row] * cols, result, row * cols, cols);
        }

        return new Matrix(result, rows, cols);
    }

    @Override
    public Tensor<?> gradient(Variable<?> contextParent, ComputationContext ctx) {
        Tensor<?> result = ctx.data(contextParent).createWithSameDimensions();

        double[] selfGradient = ctx.gradient(this).data();
        for (int row = 0; row < rows; row++) {
            int childRow = batchIds[row];
            for (int col = 0; col < cols; col++) {
                result.addDataAt(
                    childRow * cols + col,
                    selfGradient[row * cols + col]
                );
            }
        }

        return result;
    }
}
