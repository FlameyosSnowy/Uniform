package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.ContextDynamicSupplier;
import io.github.flameyossnowy.uniform.core.resolvers.ContextDynamicTypeSupplier;
import io.github.flameyossnowy.uniform.core.resolvers.ResolutionContext;

@ContextDynamicSupplier(Shape.class)
public final class ShapeSupplier implements ContextDynamicTypeSupplier<Shape> {
    @Override
    public Class<? extends Shape> supply(ResolutionContext context) {
        return Circle.class;
    }
}
