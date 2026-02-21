package me.flame.uniform.json.writers;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface JsonWriter {
    CompletableFuture<Integer> write(ByteBuffer buffer, Path path);

    default CompletableFuture<Integer> write(@NotNull String buffer, Path path) {
        return write(ByteBuffer.wrap(buffer.getBytes(StandardCharsets.UTF_8)), path);
    }
}
