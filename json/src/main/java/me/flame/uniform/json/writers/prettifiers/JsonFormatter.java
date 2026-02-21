package me.flame.uniform.json.writers.prettifiers;

import me.flame.turboscanner.ByteUtf8Validator;
import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;
import me.flame.uniform.json.exceptions.JsonException;

import java.nio.ByteBuffer;
import java.nio.file.Path;

public final class JsonFormatter {

    private final VectorByteScanner scanner = new VectorByteScanner();
    private final ByteUtf8Validator utf8Validator = new ByteUtf8Validator();

    private final Path filePath;
    private final int indentSize;

    private int line = 1;
    private int column = 1;

    public JsonFormatter(Path filePath, int indentSize) {
        this.filePath = filePath;
        this.indentSize = indentSize;
    }

    public ByteBuffer format(ByteBuffer buffer) {
        byte[] input = toArray(buffer);

        utf8Validator.reset();
        utf8Validator.validate(input, 0, input.length);
        if (utf8Validator.hasError()) {
            throw error("Invalid UTF-8");
        }

        ScanResult scan = ScanResult.create(input.length);
        scanner.scan(input, 0, input.length, scan);

        if (scan.isUtf8Error()) {
            throw error("UTF-8 structural error");
        }

        int estimated = input.length + (input.length >>> 1); // rough estimate for newlines/indent
        ByteBuffer output = ByteBuffer.allocate(estimated);

        JsonStreamEngine engine = new JsonStreamEngine(
            input,
            scan,
            output,
            this::error,
            indentSize
        );
        engine.process();

        output.flip();
        return output;
    }

    private byte[] toArray(ByteBuffer buffer) {
        if (buffer.hasArray()) return buffer.array();
        byte[] arr = new byte[buffer.remaining()];
        buffer.get(arr);
        return arr;
    }

    RuntimeException error(String message) {
        return new JsonException(
            message + " at line " + line + ", column " + column +
                (filePath != null ? " in " + filePath : "")
        );
    }
}