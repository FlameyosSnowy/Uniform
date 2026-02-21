package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.ContextDynamicSupplier;
import me.flame.uniform.core.resolvers.ContextDynamicTypeSupplier;
import me.flame.uniform.core.resolvers.ResolutionContext;

@ContextDynamicSupplier(Shape.class)
public final class ShapeSupplier implements ContextDynamicTypeSupplier<Shape> {
    @Override
    public Class<? extends Shape> supply(ResolutionContext context) {
        return Circle.class;
    }
}
