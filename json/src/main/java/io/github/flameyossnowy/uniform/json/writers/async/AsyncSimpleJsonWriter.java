package io.github.flameyossnowy.uniform.json.writers.async;

import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.tokenizer.JsonValidator;
import io.github.flameyossnowy.uniform.json.writers.JsonWriter;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousFileChannel;
import java.nio.channels.CompletionHandler;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.CompletableFuture;

public class AsyncSimpleJsonWriter implements JsonWriter {
    @Override
    public CompletableFuture<Integer> write(ByteBuffer buffer, Path path) throws JsonException {
        return CompletableFuture
            .runAsync(() -> {
                JsonValidator jsonValidator = new JsonValidator(path.toAbsolutePath());
                jsonValidator.validate(buffer);
            })
            .thenCompose((ignored) -> {
                CompletableFuture<Integer> future = new CompletableFuture<>();
                try (AsynchronousFileChannel channel = AsynchronousFileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.CREATE)) {
                    CompletionHandler<Integer, CompletableFuture<Integer>> handler = new CompletionHandler<>() {
                        @Override
                        public void completed(Integer bytesWritten, CompletableFuture<Integer> attachment) {
                            attachment.complete(bytesWritten);
                        }

                        @Override
                        public void failed(Throwable exc, CompletableFuture<Integer> attachment) {
                            attachment.completeExceptionally(exc);
                        }
                    };

                    channel.write(buffer, 0, future, handler);
                } catch (IOException e) {
                    throw JsonException.io(e);
                }
                return future;
            });
    }
}
