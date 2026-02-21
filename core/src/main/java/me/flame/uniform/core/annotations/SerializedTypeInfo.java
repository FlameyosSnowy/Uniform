package me.flame.uniform.core.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.SOURCE)
@Target(ElementType.TYPE)
public @interface SerializedTypeInfo {
    /** JSON property name used to store the type discriminator. */
    String property() default "@type";

    /** Whether to include the discriminator when writing. */
    boolean write() default true;

    /** Whether to require the discriminator when reading. */
    boolean require() default false;
}
