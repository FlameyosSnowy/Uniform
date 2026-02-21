package me.flame.uniform.core.resolvers;

import java.lang.reflect.AnnotatedElement;

public interface ResolutionContext {
    /** Declared field/record component type. */
    Class<?> declaredType();

    /** Owning/declaring class that contains this member. */
    Class<?> ownerType();

    /** Name of the logical serialized property. */
    String propertyName();

    /** Access to annotations on the member when available. */
    AnnotatedElement annotatedElement();
}
