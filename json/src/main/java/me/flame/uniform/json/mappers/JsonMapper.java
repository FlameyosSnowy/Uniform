package me.flame.uniform.json.mappers;

import me.flame.uniform.json.parser.lowlevel.JsonCursor;

@FunctionalInterface
public interface JsonMapper<T> {
    T map(JsonCursor cursor);
}
