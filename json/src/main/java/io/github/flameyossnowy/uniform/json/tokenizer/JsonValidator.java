package io.github.flameyossnowy.uniform.json.tokenizer;

import me.flame.turboscanner.ByteUtf8Validator;
import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;
import io.github.flameyossnowy.uniform.json.exceptions.Exceptions;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JsonValidator {

    private final VectorByteScanner scanner = new VectorByteScanner();
    private final ByteUtf8Validator utf8Validator = new ByteUtf8Validator();

    private int line;
    private int column;
    private final Path filePath;

    private List<Throwable> exceptions;

    private List<Throwable> getExceptions() {
        if (exceptions == null) {
            this.exceptions = new ArrayList<>(8);
        }
        return exceptions;
    }

    public JsonValidator(Path filePath) {
        this.filePath = filePath;
        this.line = 1;
        this.column = 1;
    }

    public void validate(@NotNull ByteBuffer buffer) {
        byte[] input = toArray(buffer);

        // Validate UTF-8 correctness
        utf8Validator.reset();
        utf8Validator.validate(input, 0, input.length);
        if (utf8Validator.hasError()) {
            getExceptions().add(error("Invalid UTF-8"));
        }

        // Scan structural elements (for efficiency)
        ScanResult scan = ScanResult.create(input.length);
        scanner.scan(input, 0, input.length, scan);

        if (scan.isUtf8Error()) {
            getExceptions().add(error("UTF-8 structural error"));
        }

        // Validate JSON structure linearly
        validateStructure(input, scan);

        if (!exceptions.isEmpty()) {
            throw Exceptions.mergeExceptions("Invalid JSON", exceptions);
        }
    }

    private void validateStructure(byte[] input, ScanResult scan) {
        long[] structural = scan.getStructuralMask();
        long[] inside = scan.getInsideStringMask();

        int depth = 0;

        for (int i = 0; i < input.length; i++) {

            if (isMasked(i, inside)) {
                // Inside a string, just skip
                updatePosition(input[i]);
                continue;
            }

            byte b = input[i];

            switch (b) {
                case '{', '[' -> depth++;
                case '}', ']' -> {
                    depth--;
                    if (depth < 0) getExceptions().add(error("Mismatched closing bracket"));
                }
                case '"', ':', ',', 't', 'f', 'n', '-', '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', '.', 'e', 'E' -> {
                    // Allowed inside JSON, nothing to do
                }
                case ' ', '\r', '\n', '\t' -> {
                    // Whitespace is ignored
                }
                default -> getExceptions().add(error("Invalid character: " + (char) b));
            }

            updatePosition(b);
        }

        if (depth != 0) getExceptions().add(error("Unbalanced brackets/braces"));
    }

    private void updatePosition(byte b) {
        if (b == '\n') {
            line++;
            column = 1;
        } else {
            column++;
        }
    }

    private byte[] toArray(ByteBuffer buffer) {
        if (buffer.hasArray()) return buffer.array();
        byte[] arr = new byte[buffer.remaining()];
        buffer.get(arr);
        return arr;
    }

    private @NotNull RuntimeException error(String message) {
        return new JsonException(
            message + " at line " + line + ", column " + column +
                (filePath != null ? " in " + filePath : "")
        );
    }

    private boolean isMasked(int index, long[] mask) {
        return ((mask[index >>> 6] >>> (index & 63)) & 1L) != 0;
    }
}
