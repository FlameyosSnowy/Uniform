package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.writers.JsonStringWriter;

public interface JsonWriterMapper<T> {
    void writeTo(JsonStringWriter out, T value);

    default String write(T value) {
        if (value == null) return "null";
        JsonStringWriter out = new JsonStringWriter(64);
        writeTo(out, value);
        return out.finish();
    }
}
