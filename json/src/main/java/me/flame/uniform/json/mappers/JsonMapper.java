package me.flame.uniform.json.mappers;

import me.flame.uniform.json.parser.JsonReadCursor;

@FunctionalInterface
public interface JsonMapper<T> {
    T map(JsonReadCursor cursor);
}
