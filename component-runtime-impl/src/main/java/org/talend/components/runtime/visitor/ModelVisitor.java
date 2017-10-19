// ============================================================================
//
// Copyright (C) 2006-2017 Talend Inc. - www.talend.com
//
// This source code is available under agreement available at
// %InstallDIR%\features\org.talend.rcp.branding.%PRODUCTNAME%\%PRODUCTNAME%license.txt
//
// You should have received a copy of the agreement
// along with this program; if not, write to Talend SA
// 9 rue Pages 92150 Suresnes, France
//
// ============================================================================
package org.talend.components.runtime.visitor;

import static java.util.stream.Collectors.toList;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import org.talend.component.api.input.Assessor;
import org.talend.component.api.input.Emitter;
import org.talend.component.api.input.PartitionSize;
import org.talend.component.api.input.PartitionMapper;
import org.talend.component.api.input.Producer;
import org.talend.component.api.input.Split;
import org.talend.component.api.processor.AfterGroup;
import org.talend.component.api.processor.BeforeGroup;
import org.talend.component.api.processor.ElementListener;
import org.talend.component.api.processor.Output;
import org.talend.component.api.processor.OutputEmitter;
import org.talend.component.api.processor.Processor;

public class ModelVisitor {

    public void visit(final Class<?> type, final ModelListener listener, final boolean validate) {
        if (getSupportedComponentTypes().noneMatch(type::isAnnotationPresent)) { // unlikely but just in case
            return;
        }
        if (getSupportedComponentTypes().filter(type::isAnnotationPresent).count() != 1) { // > 1 actually
            throw new IllegalArgumentException("You can't mix @Emitter, @PartitionMapper and @Processor on " + type);
        }

        if (type.isAnnotationPresent(PartitionMapper.class)) {
            if (validate) {
                validatePartitionMapper(type);
            }
            listener.onPartitionMapper(type, type.getAnnotation(PartitionMapper.class));
        } else if (type.isAnnotationPresent(Emitter.class)) {
            if (validate) {
                validateEmitter(type);
            }
            listener.onEmitter(type, type.getAnnotation(Emitter.class));
        } else if (type.isAnnotationPresent(Processor.class)) {
            if (validate) {
                validateProcessor(type);
            }
            listener.onProcessor(type, type.getAnnotation(Processor.class));
        }
    }

    private void validatePartitionMapper(final Class<?> type) {
        if (Stream.of(type.getMethods()).filter(m -> getPartitionMapperMethods().anyMatch(m::isAnnotationPresent))
                .flatMap(m -> getPartitionMapperMethods().filter(m::isAnnotationPresent)).distinct().count() != 3) {
            throw new IllegalArgumentException(
                    type + " partition mapper must have exactly one @Assessor, one @Split and one @Emitter methods");
        }

        //
        // now validate the 2 methods of the mapper
        //

        Stream.of(type.getMethods()).filter(m -> m.isAnnotationPresent(Assessor.class)).forEach(m -> {
            if (m.getParameterCount() > 0) {
                throw new IllegalArgumentException(m + " must not have any parameter");
            }
        });
        Stream.of(type.getMethods()).filter(m -> m.isAnnotationPresent(Split.class)).forEach(m -> {
            // for now we could inject it by default but to ensure we can inject more later we must do that validation
            if (Stream.of(m.getParameters()).filter(
                    p -> !p.isAnnotationPresent(PartitionSize.class) || (p.getType() != long.class && p.getType() != int.class))
                    .count() > 0) {
                throw new IllegalArgumentException(m + " must not have any parameter without @PartitionSize");
            }
            final Type splitReturnType = m.getGenericReturnType();
            if (!ParameterizedType.class.isInstance(splitReturnType)) {
                throw new IllegalArgumentException(m + " must return a Collection<" + type.getName() + ">");
            }

            final ParameterizedType splitPt = ParameterizedType.class.cast(splitReturnType);
            if (!Class.class.isInstance(splitPt.getRawType())
                    || !Collection.class.isAssignableFrom(Class.class.cast(splitPt.getRawType()))) {
                throw new IllegalArgumentException(m + " must return a List of partition mapper, found: " + splitPt);
            }

            final Type arg = splitPt.getActualTypeArguments().length != 1 ? null : splitPt.getActualTypeArguments()[0];
            if (!Class.class.isInstance(arg) || !type.isAssignableFrom(Class.class.cast(arg))) {
                throw new IllegalArgumentException(m + " must return a Collection<" + type.getName() + "> but found: " + arg);
            }
        });
        Stream.of(type.getMethods()).filter(m -> m.isAnnotationPresent(Emitter.class)).forEach(m -> {
            // for now we don't support injection propagation since the mapper should already own all the config
            if (m.getParameterCount() > 0) {
                throw new IllegalArgumentException(m + " must not have any parameter");
            }
        });
    }

    private void validateEmitter(final Class<?> input) {
        final List<Method> producers = Stream.of(input.getMethods()).filter(m -> m.isAnnotationPresent(Producer.class))
                .collect(toList());
        if (producers.size() != 1) {
            throw new IllegalArgumentException(input + " must have a single @Producer method");
        }

        if (producers.get(0).getParameterCount() > 0) {
            throw new IllegalArgumentException(producers.get(0) + " must not have any parameter");
        }
    }

    private void validateProcessor(final Class<?> input) {
        final List<Method> producers = Stream.of(input.getMethods()).filter(m -> m.isAnnotationPresent(ElementListener.class))
                .collect(toList());
        if (producers.size() != 1) {
            throw new IllegalArgumentException(input + " must have a single @ElementListener method");
        }

        if (Stream.of(producers.get(0).getParameters()).filter(p -> {
            if (p.isAnnotationPresent(Output.class)) {
                if (!ParameterizedType.class.isInstance(p.getParameterizedType())) {
                    throw new IllegalArgumentException("@Output parameter must be of type OutputEmitter");
                }
                final ParameterizedType pt = ParameterizedType.class.cast(p.getParameterizedType());
                if (OutputEmitter.class != pt.getRawType()) {
                    throw new IllegalArgumentException("@Output parameter must be of type OutputEmitter");
                }
                return false;
            }
            return true;
        }).count() != 1) {
            throw new IllegalArgumentException(input + " doesn't have the input parameter on its producer method");
        }

        Stream.of(input.getMethods())
                .filter(m -> m.isAnnotationPresent(BeforeGroup.class) || m.isAnnotationPresent(AfterGroup.class)).forEach(m -> {
                    if (m.getParameterCount() > 0) {
                        throw new IllegalArgumentException(m + " must not have any parameter");
                    }
                });
    }

    private Stream<Class<? extends Annotation>> getPartitionMapperMethods() {
        return Stream.of(Assessor.class, Split.class, Emitter.class);
    }

    private Stream<Class<? extends Annotation>> getSupportedComponentTypes() {
        return Stream.of(Emitter.class, PartitionMapper.class, Processor.class);
    }
}
