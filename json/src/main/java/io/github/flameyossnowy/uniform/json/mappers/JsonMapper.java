package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;

@FunctionalInterface
public interface JsonMapper<T> {
    T map(JsonReadCursor cursor);
}
