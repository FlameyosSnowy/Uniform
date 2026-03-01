package me.flame.uniform.json.writers.prettifiers;

import me.flame.turboscanner.ByteUtf8Validator;
import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;

import me.flame.uniform.json.exceptions.Exceptions;
import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.JsonConfig;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JsonFormatter {
    private final VectorByteScanner scanner        = new VectorByteScanner();
    private final ByteUtf8Validator  utf8Validator = new ByteUtf8Validator();

    private final Path       filePath;
    private final JsonConfig config;

    public JsonFormatter(Path filePath, JsonConfig config) {
        this.filePath = filePath;
        this.config   = config;
    }

    public JsonFormatter(Path filePath, int indentSize) {
        this.filePath = filePath;
        this.config   = new JsonConfig(false, indentSize, null, null);
    }

    public ByteBuffer format(ByteBuffer buffer) {
        final byte[]              input  = toArray(buffer);
        final List<JsonException> errors = new ArrayList<>();

        utf8Validator.reset();
        utf8Validator.validate(input, 0, input.length);
        if (utf8Validator.hasError()) {
            errors.add(buildError("Invalid UTF-8", -1, null));
        }

        final ScanResult scan = ScanResult.create(input.length);
        scanner.scan(input, 0, input.length, scan);

        if (scan.isUtf8Error()) {
            throw buildError("UTF-8 structural error", -1, null);
        }
        if (!errors.isEmpty()) {
            throw Exceptions.mergeExceptions("Formatting failed", errors);
        }

        // Estimate: original length + 50 % headroom for newlines + indentation.
        final int estimated  = input.length + (input.length >>> 1);
        final int indentSize = config != null ? config.indentSize() : 4;

        final JsonStreamEngine engine = new JsonStreamEngine(
            input, scan, estimated,
            (message, offset) -> errors.add(buildError(message, offset, input)),
            indentSize
        );
        engine.process();

        if (!errors.isEmpty()) {
            throw Exceptions.mergeExceptions("Formatting completed with errors", errors);
        }

        return engine.getOutput();
    }

    public String formatToString(ByteBuffer buffer) {
        ByteBuffer formatted = format(buffer);
        byte[] outputBytes = new byte[formatted.remaining()];
        formatted.get(outputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }

    public String formatToString(byte[] buffer) {
        ByteBuffer formatted = format(ByteBuffer.wrap(buffer));
        byte[] outputBytes = new byte[formatted.remaining()];
        formatted.get(outputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }

    public ByteBuffer format(byte[] buffer) {
        return format(ByteBuffer.wrap(buffer));
    }

    public String formatToString(String buffer) {
        ByteBuffer formatted = format(ByteBuffer.wrap(buffer.getBytes(StandardCharsets.UTF_8)));
        byte[] outputBytes = new byte[formatted.remaining()];
        formatted.get(outputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }

    public ByteBuffer format(String buffer) {
        return format(ByteBuffer.wrap(buffer.getBytes(StandardCharsets.UTF_8)));
    }

    /**
     * Converts a byte offset into a (line, column) pair and builds a JsonException.
     * {@code offset == -1} means no position is available.
     */
    private JsonException buildError(String message, int offset, byte[] input) {
        final String suffix = filePath != null ? " in " + filePath : "";
        if (offset < 0 || input == null) {
            return new JsonException(message + suffix);
        }

        int line = 1, col = 1;
        final int limit = Math.min(offset, input.length);
        for (int k = 0; k < limit; k++) {
            if (input[k] == '\n') { line++; col = 1; }
            else                  { col++;            }
        }
        return new JsonException(message + " at line " + line + ", column " + col + suffix);
    }

    private static byte[] toArray(ByteBuffer buffer) {
        final byte[] arr;

        if (buffer.hasArray()) {
            final byte[] backing = buffer.array();
            final int    offset  = buffer.arrayOffset() + buffer.position();
            final int    length  = buffer.remaining();
            if (offset == 0 && length == backing.length) {
                arr = backing;
            } else {
                arr = new byte[length];
                System.arraycopy(backing, offset, arr, 0, length);
            }
        } else {
            arr = new byte[buffer.remaining()];
            buffer.get(arr);
        }

         return arr;
    }
}