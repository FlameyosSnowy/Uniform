package io.github.flameyossnowy.uniform.json.writers.prettifiers;

import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.ReflectionConfig;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import java.util.concurrent.CompletableFuture;

public class DefaultPrettifyEngine implements PrettifyEngine {
    private final JsonConfig config;

    public DefaultPrettifyEngine(JsonConfig config) {
        this.config = config;
    }

    // Convenience constructor for callers that only care about indent size
    public DefaultPrettifyEngine(int indentSize) {
        this.config = new JsonConfig(false, indentSize, null, null, ReflectionConfig.DEFAULT);
    }

    @Override
    public CompletableFuture<ByteBuffer> prettify(ByteBuffer uglyBuffer, Path path) {
        return CompletableFuture.completedFuture(
            new JsonFormatter(path, config).format(uglyBuffer)
        );
    }
}