package me.flame.uniform.json.writers.prettifiers;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public interface PrettifyEngine {
    CompletableFuture<ByteBuffer> prettify(ByteBuffer uglyBuffer, Path path);
}
