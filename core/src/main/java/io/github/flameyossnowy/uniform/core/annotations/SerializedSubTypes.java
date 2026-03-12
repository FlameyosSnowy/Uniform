package io.github.flameyossnowy.uniform.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SerializedSubTypes {
    Type[] value();

    @interface Type {
        Class<?> value();
        String name() default "";
    }
}
