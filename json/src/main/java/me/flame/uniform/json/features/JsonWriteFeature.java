package me.flame.uniform.json.features;

import java.util.EnumSet;

/**
 * @see JsonReadFeature
 */
public enum JsonWriteFeature {
    WRITE_DATES_AS_TIMESTAMPS(true),
    PRETTY_PRINTING(false),
    ESCAPE_UNICODE(false),
    WRITE_ENUMS_USING_TO_STRING(false),
    WRITE_SINGLE_ELEM_ARRAYS_UNWRAPPED(false),
    ORDER_MAP_ENTRIES_BY_KEYS(false),
    INDENT_OUTPUT(false),
    WRITE_NUMBERS_AS_STRINGS(false),
    WRITE_NULL_MAP_VALUES(true),
    WRITE_EMPTY_JSON_ARRAYS(true),
    WRITE_EMPTY_JSON_OBJECTS(true);

    private final boolean defaultValue;

    public static final EnumSet<JsonWriteFeature> ALL = EnumSet.allOf(JsonWriteFeature.class);

    JsonWriteFeature(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isDefaultValue() {
        return defaultValue;
    }
}
