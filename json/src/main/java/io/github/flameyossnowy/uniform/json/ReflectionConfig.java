package io.github.flameyossnowy.uniform.json;

public record ReflectionConfig(
    boolean enabled
) {
    public static final ReflectionConfig DEFAULT = new ReflectionConfig(true);
    public static final ReflectionConfig DISABLED = new ReflectionConfig(false);
}