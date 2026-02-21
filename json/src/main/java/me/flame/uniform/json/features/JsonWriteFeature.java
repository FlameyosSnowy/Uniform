package me.flame.uniform.json.features;

import java.util.EnumSet;

public enum JsonWriteFeature {
    WRITE_DATES_AS_TIMESTAMPS(true);

    private final boolean defaultValue;

    public static final EnumSet<JsonWriteFeature> ALL = EnumSet.allOf(JsonWriteFeature.class);

    JsonWriteFeature(boolean defaultValue) {
        this.defaultValue = defaultValue;
    }

    public boolean isDefaultValue() {
        return defaultValue;
    }
}
