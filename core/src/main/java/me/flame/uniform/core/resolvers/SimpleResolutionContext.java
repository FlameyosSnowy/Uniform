package me.flame.uniform.core.resolvers;

import java.lang.reflect.AnnotatedElement;

public record SimpleResolutionContext(
    Class<?> declaredType,
    Class<?> ownerType,
    String propertyName,
    AnnotatedElement annotatedElement
) implements ResolutionContext {
}
