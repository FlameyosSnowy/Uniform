package io.github.flameyossnowy.uniform.core.resolvers;

public interface TypeResolver<T> {
    /**
     * Resolve a concrete type to instantiate for a declared abstract/interface type.
     */
    Class<? extends T> resolve(ResolutionContext context);
}
