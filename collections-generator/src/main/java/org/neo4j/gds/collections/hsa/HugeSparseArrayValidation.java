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
package org.neo4j.gds.collections.hsa;

import com.google.auto.common.MoreElements;
import com.squareup.javapoet.ArrayTypeName;
import com.squareup.javapoet.TypeName;
import org.neo4j.gds.annotation.ValueClass;
import org.neo4j.gds.collections.HugeSparseArray;

import javax.annotation.processing.Messager;
import javax.lang.model.element.Element;
import javax.lang.model.element.Name;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.Optional;
import java.util.function.LongConsumer;

import static com.google.auto.common.AnnotationMirrors.getAnnotationValue;

final class HugeSparseArrayValidation {

    private final Types typeUtils;
    private final Elements elementUtils;
    private final Messager messager;

    HugeSparseArrayValidation(Types typeUtils, Elements elementUtils, Messager messager) {
        this.typeUtils = typeUtils;
        this.elementUtils = elementUtils;
        this.messager = messager;
    }

    Optional<Spec> validate(Element element) {
        var annotationMirror = MoreElements.getAnnotationMirror(element, HugeSparseArray.class).get();
        var valueType = (TypeMirror) getAnnotationValue(annotationMirror, "valueType").getValue();
        var isArrayType = valueType.getKind() == TypeKind.ARRAY;

        if (!isValidValueType(valueType)) {
            return Optional.empty();
        }

        var pageShift = (int) getAnnotationValue(annotationMirror, "pageShift").getValue();
        var longConsumerType = elementUtils.getTypeElement(LongConsumer.class.getName()).asType();
        var drainingIteratorType = elementUtils.getTypeElement("org.neo4j.gds.collections.DrainingIterator").asType();

        var elementValidator = new ElementValidator(
            typeUtils,
            element.asType(),
            longConsumerType,
            drainingIteratorType,
            isArrayType,
            this.messager
        );

        if (!isValid(element, elementValidator, valueType)) {
            return Optional.empty();
        }

        var builderType = elementValidator.builderType().asType();
        var rootPackage = rootPackage(element);

        var spec = ImmutableSpec.builder()
            .element(element)
            .valueType(valueType)
            .builderType(builderType)
            .rootPackage(rootPackage)
            .pageShift(pageShift)
            .build();

        return Optional.of(spec);
    }

    private Name rootPackage(Element element) {
        return elementUtils.getPackageOf(element).getQualifiedName();
    }

    private boolean isValid(Element element, ElementValidator validator, TypeMirror annotationValue) {
        return element
            .getEnclosedElements()
            .stream()
            // We do not use `allMatch` in order to run all validations and not stop on the first failing one.
            .map(e -> e.accept(validator, annotationValue))
            .reduce(true, (a, b) -> a && b);
    }

    private boolean isValidValueType(TypeMirror valueType) {
        var isPrimitive = valueType.getKind().isPrimitive();
        var isArray = valueType.getKind() == TypeKind.ARRAY;

        var errorMsg = "value type must be a primitive type or a primitive array type";

        if (!isPrimitive && !isArray) {
            messager.printMessage(Diagnostic.Kind.ERROR, errorMsg);
            return false;
        }

        if (isArray) {
            var componentType = ((ArrayTypeName) TypeName.get(valueType)).componentType;
            if (!componentType.isPrimitive()) {
                messager.printMessage(Diagnostic.Kind.ERROR, errorMsg);
                return false;
            }
        }

        return true;
    }

    @ValueClass
    public interface Spec {
        Element element();

        TypeMirror valueType();

        TypeMirror builderType();

        int pageShift();

        Name rootPackage();

        default String className() {
            return element().getSimpleName() + "Son";
        }
    }
}
