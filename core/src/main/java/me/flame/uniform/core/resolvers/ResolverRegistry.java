package me.flame.uniform.core.resolvers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ResolverRegistry {
    private static final Map<Class<?>, TypeResolver<?>> RESOLVERS = new ConcurrentHashMap<>(64);
    private static final Map<Class<?>, ContextDynamicTypeSupplier<?>> SUPPLIERS = new ConcurrentHashMap<>(64);

    private ResolverRegistry() {
    }

    public static <T> void register(Class<T> declaredType, TypeResolver<? extends T> resolver) {
        RESOLVERS.put(declaredType, resolver);
    }

    public static <T> void registerSupplier(Class<T> declaredType, ContextDynamicTypeSupplier<? extends T> supplier) {
        SUPPLIERS.put(declaredType, supplier);
    }

    @SuppressWarnings("unchecked")
    public static <T> TypeResolver<T> get(Class<T> declaredType) {
        return (TypeResolver<T>) RESOLVERS.get(declaredType);
    }

    @SuppressWarnings("unchecked")
    public static <T> ContextDynamicTypeSupplier<T> getSupplier(Class<T> declaredType) {
        return (ContextDynamicTypeSupplier<T>) SUPPLIERS.get(declaredType);
    }
}
