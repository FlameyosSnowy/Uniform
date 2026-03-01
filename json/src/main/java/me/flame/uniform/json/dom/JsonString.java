package me.flame.uniform.json.dom;

import org.jetbrains.annotations.NotNull;

public record JsonString(String value) implements JsonValue {
    @Override
    public @NotNull String toString() {
        // Minimal escaping – expand as needed for a production parser.
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}