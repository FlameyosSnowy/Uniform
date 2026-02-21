package me.flame.uniform.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(value = {ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.METHOD})
public @interface SerializedField {
    String value() default "";
}
