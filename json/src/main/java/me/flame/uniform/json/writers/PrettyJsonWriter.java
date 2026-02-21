package me.flame.uniform.json.writers;

import me.flame.uniform.json.exceptions.Exceptions;
import me.flame.uniform.json.tokenizer.JsonTokenizer;
import me.flame.uniform.json.tokenizer.JsonTokensResult;
import me.flame.uniform.json.writers.prettifiers.DefaultPrettifyEngine;
import me.flame.uniform.json.writers.prettifiers.PrettifyEngine;
import me.flame.uniform.json.writers.prettifiers.async.AsyncDefaultPrettifyEngine;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class PrettyJsonWriter implements JsonWriter {
    private final JsonWriter writer;
    private final PrettifyEngine prettifyEngine;

    public PrettyJsonWriter(JsonWriter writer, PrettifyEngine engine) {
        this.writer = writer;
        this.prettifyEngine = engine;
    }

    @Override
    public CompletableFuture<Integer> write(ByteBuffer uglyBuffer, Path path) {
        return prettifyEngine.prettify(uglyBuffer, path)
            .thenCompose((buffer) -> writer.write(buffer, path));
    }
}
