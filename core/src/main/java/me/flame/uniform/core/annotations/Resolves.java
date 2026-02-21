package me.flame.uniform.core.annotations;

import me.flame.uniform.core.resolvers.TypeResolver;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target({ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD, ElementType.TYPE_USE})
public @interface Resolves {
    Class<? extends TypeResolver<?>> value();
}
