package me.flame.uniform.core.resolvers;

public interface ContextDynamicTypeSupplier<T> {
    /**
     * Choose a concrete type for a declared interface/abstract type.
     */
    Class<? extends T> supply(ResolutionContext context);
}
