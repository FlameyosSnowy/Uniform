package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.writers.JsonStringWriter;

import java.nio.charset.StandardCharsets;

public interface JsonWriterMapper<T> {
    byte[] NULL_BYTES = "null".getBytes(StandardCharsets.UTF_8);

    void writeTo(JsonStringWriter out, T value);

    default String write(T value) {
        if (value == null) return "null";
        JsonStringWriter out = new JsonStringWriter(64);
        writeTo(out, value);
        return out.finish();
    }

    default byte[] writeAsBytes(T value) {
        if (value == null) return NULL_BYTES;
        JsonStringWriter out = new JsonStringWriter(64);
        writeTo(out, value);
        return out.finishAsBytes();
    }
}
