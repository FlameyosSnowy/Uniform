package me.flame.uniform.json.writers;

import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.tokenizer.JsonValidator;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public class SimpleJsonWriter implements JsonWriter {
    @Override
    public CompletableFuture<Integer> write(ByteBuffer buffer, Path path) throws JsonException {
        try (WritableByteChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            return CompletableFuture.completedFuture(channel.write(buffer));
        } catch (IOException e) {
            throw JsonException.io(e);
        }
    }
}
