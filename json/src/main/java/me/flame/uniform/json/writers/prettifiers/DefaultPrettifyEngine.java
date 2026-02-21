package me.flame.uniform.json.writers.prettifiers;

import java.nio.ByteBuffer;
import java.nio.file.Path;

import java.util.concurrent.CompletableFuture;

public class DefaultPrettifyEngine implements PrettifyEngine {
    private final int indentSize;

    public DefaultPrettifyEngine(int indentSize) {
        this.indentSize = indentSize;
    }

    @Override
    public CompletableFuture<ByteBuffer> prettify(ByteBuffer uglyBuffer, Path path) {
        JsonFormatter jsonFormatter = new JsonFormatter(path, indentSize);
        return CompletableFuture.completedFuture(
            jsonFormatter.format(uglyBuffer)
        );
    }
}
