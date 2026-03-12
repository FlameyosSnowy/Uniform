package io.github.flameyossnowy.uniform.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface ContextDynamicSupplier {
    /** Declared abstract/interface type this supplier can provide a concrete type for. */
    Class<?> value();
}
