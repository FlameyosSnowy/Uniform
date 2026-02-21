package me.flame.uniform.json.exceptions;

import java.util.List;

public class Exceptions {
    public static JsonException mergeExceptions(String message, List<? extends Throwable> errors) {
        if (errors.isEmpty()) {
            return new JsonException(message);
        }

        JsonException merged = new JsonException(message);

        for (Throwable t : errors) {
            merged.addSuppressed(t);
        }

        return merged;
    }
}
