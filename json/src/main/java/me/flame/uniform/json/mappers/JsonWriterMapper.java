package me.flame.uniform.json.mappers;

import me.flame.uniform.json.writers.JsonStringWriter;

public interface JsonWriterMapper<T> {
    void writeTo(JsonStringWriter out, T value);

    default String write(T value) {
        if (value == null) return "null";
        JsonStringWriter out = new JsonStringWriter(64);
        writeTo(out, value);
        return out.finish();
    }
}
