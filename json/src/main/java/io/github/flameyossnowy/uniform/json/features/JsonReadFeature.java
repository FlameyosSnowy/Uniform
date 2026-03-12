package io.github.flameyossnowy.uniform.json.features;

import java.util.EnumSet;

/**
 * @see JsonWriteFeature
 */
public enum JsonReadFeature {
    ALLOW_JAVA_COMMENTS(false),
    ALLOW_YAML_COMMENTS(false),
    ALLOW_SINGLE_QUOTES(false),
    ALLOW_UNQUOTED_FIELD_NAMES(false),
    ALLOW_UNESCAPED_CONTROL_CHARS(false),
    ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER(false),
    ALLOW_LEADING_ZEROS_FOR_NUMBERS(false),
    ALLOW_NON_NUMERIC_NUMBERS(false),
    ALLOW_MISSING_VALUES(false),
    ALLOW_TRAILING_COMMA(false),
    STRICT_DUPLICATE_DETECTION(true),
    IGNORE_UNDEFINED(false),
    WRAP_EXCEPTIONS(true);

    private final boolean defaultValue;

    public static final EnumSet<JsonReadFeature> ALL = EnumSet.allOf(JsonReadFeature.class);

    JsonReadFeature(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isDefaultValue() {
        return defaultValue;
    }
}
