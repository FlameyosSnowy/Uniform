package io.github.flameyossnowy.uniform.json.writers.prettifiers.async;

import io.github.flameyossnowy.uniform.json.writers.prettifiers.DefaultPrettifyEngine;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.PrettifyEngine;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

public class AsyncDefaultPrettifyEngine implements PrettifyEngine {
    private final DefaultPrettifyEngine defaultPrettifyEngine;

    public AsyncDefaultPrettifyEngine(DefaultPrettifyEngine defaultPrettifyEngine) {
        this.defaultPrettifyEngine = defaultPrettifyEngine;
    }

    @Override
    public CompletableFuture<ByteBuffer> prettify(ByteBuffer uglyBuffer, Path path) {
        return CompletableFuture
            .supplyAsync(() -> defaultPrettifyEngine.prettify(uglyBuffer, path))
            .thenCompose(Function.identity());
    }
}
