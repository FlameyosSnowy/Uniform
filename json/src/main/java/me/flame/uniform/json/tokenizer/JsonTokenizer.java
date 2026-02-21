package me.flame.uniform.json.tokenizer;

import me.flame.turboscanner.ByteUtf8Validator;
import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;
import me.flame.uniform.json.exceptions.JsonException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class JsonTokenizer {

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

    public JsonTokenizer(Path filePath) {
        this.filePath = filePath;
        this.line = 1;
        this.column = 1;
    }

    public JsonTokensResult tokenize(@NotNull ByteBuffer buffer) {
        byte[] input = toArray(buffer);

        utf8Validator.reset();
        utf8Validator.validate(input, 0, input.length);
        if (utf8Validator.hasError()) {
            getExceptions().add(error("Invalid UTF-8"));
        }

        ScanResult scan = ScanResult.create(input.length);
        scanner.scan(input, 0, input.length, scan);

        if (scan.isUtf8Error()) {
            getExceptions().add(error("UTF-8 structural error"));
        }

        return buildTokens(input, scan);
    }

    private JsonTokensResult buildTokens(byte[] input, ScanResult scan) {

        List<Token> tokens = new ArrayList<>();
        int depth = 0;

        long[] structural = scan.getStructuralMask();
        int lanes = scan.getLanes();

        // We iterate byte-by-byte to keep line/column correct.
        for (int i = 0; i < input.length; i++) {

            byte b = input[i];

            if (!isMasked(i, structural)) {
                updatePosition(b);
                continue;
            }

            switch (b) {
                case '{' -> {
                    tokens.add(Token.of(Token.Type.OBJECT_START, i, i + 1, depth));
                    depth++;
                }
                case '}' -> {
                    depth--;
                    tokens.add(Token.of(Token.Type.OBJECT_END, i, i + 1, depth));
                }
                case '[' -> {
                    tokens.add(Token.of(Token.Type.ARRAY_START, i, i + 1, depth));
                    depth++;
                }
                case ']' -> {
                    depth--;
                    tokens.add(Token.of(Token.Type.ARRAY_END, i, i + 1, depth));
                }
                case ':' ->
                    tokens.add(Token.of(Token.Type.COLON, i, i + 1, depth));
                case ',' ->
                    tokens.add(Token.of(Token.Type.COMMA, i, i + 1, depth));
            }

            updatePosition(b);
        }

        parseStrings(scan, tokens);
        parseScalars(input, scan, tokens);

        return JsonTokensResult.createTokens(tokens);
    }

    private void parseStrings(ScanResult scan, List<Token> tokens) {

        long[] quoteMask = scan.getQuoteMask();
        int lanes = scan.getLanes();

        for (int lane = 0; lane < lanes; lane++) {
            long mask = quoteMask[lane];

            while (mask != 0) {
                int bit = Long.numberOfTrailingZeros(mask);
                int start = (lane << 6) + bit;

                int end = findStringEnd(scan, start + 1);

                tokens.add(Token.of(Token.Type.STRING, start + 1, end, 0));

                mask &= mask - 1;
            }
        }
    }

    private void parseScalars(byte[] input, ScanResult scan, List<Token> tokens) {

        long[] inside = scan.getInsideStringMask();
        long[] structural = scan.getStructuralMask();
        int length = input.length;

        int i = 0;

        while (i < length) {

            if (isMasked(i, inside) || isMasked(i, structural)) {
                updatePosition(input[i]);
                i++;
                continue;
            }

            byte b = input[i];

            if (isDigit(b) || b == '-') {
                int start = i;
                int end = parseNumber(input, i);

                tokens.add(Token.of(Token.Type.NUMBER, start, end, 0));

                for (; i < end; i++) {
                    updatePosition(input[i]);
                }

            } else if (b == 't') {
                matchLiteral(input, i, "true");
                tokens.add(Token.of(Token.Type.TRUE, i, i + 4, 0));
                for (int j = 0; j < 4; j++) updatePosition(input[i++]);
            } else if (b == 'f') {
                matchLiteral(input, i, "false");
                tokens.add(Token.of(Token.Type.FALSE, i, i + 5, 0));
                for (int j = 0; j < 5; j++) updatePosition(input[i++]);
            } else if (b == 'n') {
                matchLiteral(input, i, "null");
                tokens.add(Token.of(Token.Type.NULL, i, i + 4, 0));
                for (int j = 0; j < 4; j++) updatePosition(input[i++]);
            } else {
                updatePosition(b);
                i++;
            }
        }
    }

    private int parseNumber(byte @NotNull [] input, int i) {

        if (input[i] == '-') i++;

        if (i >= input.length) getExceptions().add(error("Unexpected end in number"));

        if (input[i] == '0') {
            i++;
        } else {
            if (!isDigit(input[i])) getExceptions().add(error("Invalid number"));
            while (i < input.length && isDigit(input[i])) i++;
        }

        if (i < input.length && input[i] == '.') {
            i++;
            if (i >= input.length || !isDigit(input[i]))
                getExceptions().add(error("Invalid decimal number"));
            while (i < input.length && isDigit(input[i])) i++;
        }

        if (i < input.length && (input[i] == 'e' || input[i] == 'E')) {
            i++;
            if (i < input.length && (input[i] == '+' || input[i] == '-')) i++;
            if (i >= input.length || !isDigit(input[i]))
                getExceptions().add(error("Invalid exponent"));
            while (i < input.length && isDigit(input[i])) i++;
        }

        return i;
    }

    private int findStringEnd(ScanResult scan, int from) {
        long[] quoteMask = scan.getQuoteMask();
        int lane = from >>> 6;
        int bit = from & 63;

        long mask = quoteMask[lane] & (~0L << bit);

        while (true) {
            if (mask != 0) {
                int pos = Long.numberOfTrailingZeros(mask);
                return (lane << 6) + pos;
            }
            lane++;
            if (lane >= quoteMask.length) {
                getExceptions().add(error("Unterminated string"));
            }
            mask = quoteMask[lane];
        }
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
        if (buffer.hasArray()) {
            return buffer.array();
        }
        byte[] arr = new byte[buffer.remaining()];
        buffer.get(arr);
        return arr;
    }

    @Contract(value = "_ -> new", pure = true)
    private @NotNull RuntimeException error(String message) {
        return new JsonException(
            message + " at line " + line + ", column " + column +
                (filePath != null ? " in " + filePath : "")
        );
    }

    private void matchLiteral(byte[] input, int offset, String literal) {
        for (int i = 0; i < literal.length(); i++) {
            if (offset + i >= input.length ||
                input[offset + i] != literal.charAt(i)) {
                throw error("Invalid literal");
            }
        }
    }

    private boolean isMasked(int index, long[] mask) {
        return ((mask[index >>> 6] >>> (index & 63)) & 1L) != 0;
    }

    private boolean isDigit(byte b) {
        return b >= '0' && b <= '9';
    }
}
