package me.flame.uniform.json.parser.lowlevel;

import me.flame.turboscanner.*;
import me.flame.uniform.json.exceptions.JsonException;

public class JsonDecoder {

    private final ByteScanner scanner;
    private final Utf8Validator validator;
    private final ScanResult scan;

    public JsonDecoder(int maxBytes) {
        this.scanner = new VectorByteScanner();
        this.validator = new ByteUtf8Validator();
        this.scan = ScanResult.create(maxBytes);
    }

    public ScanResult decode(byte[] input) {
        scan.clear();

        int scanned = scanner.scan(input, 0, input.length, scan);
        if (scanned != input.length) {
            throw JsonException.truncated();
        }

        validate(input, validator, scan);
        return scan;
    }

    private static void validate(byte[] input, Utf8Validator validator, ScanResult scan) {

        validator.validate(input, 0, input.length);
        if (validator.hasError()) {
            throw JsonException.invalidUtf8();
        }

        validateStructure(input, scan);
        validateStrings(input, scan);
        validateNumbers(input);

        validator.reset();
    }

    private static void validateStructure(byte[] input, ScanResult scan) {
        int depth = 0;
        int length = input.length;

        for (int i = 0; i < length; i++) {
            if (!scan.isStructural(i)) continue;

            byte b = input[i];
            if (b == '{' || b == '[') depth++;
            else if (b == '}' || b == ']') depth--;

            if (depth < 0) {
                throw new JsonException("Unbalanced brackets at index " + i);
            }
        }

        if (depth != 0) {
            throw new JsonException("Unbalanced brackets at end of input");
        }
    }

    private static void validateStrings(byte[] input, ScanResult scan) {
        int length = input.length;

        for (int i = 0; i < length; i++) {
            if (!scan.isInsideString(i)) continue;

            if ((input[i] & 0xFF) < 0x20) {
                throw JsonException.unescapedControl();
            }
        }

        if (length > 0 && scan.isInsideString(length - 1)) {
            throw JsonException.unterminatedString();
        }
    }

    private static void validateNumbers(byte[] input) {
        int i = 0;
        int length = input.length;

        while (i < length) {
            byte c = input[i];

            if (c == '-' || (c >= '0' && c <= '9')) {
                i = consumeNumber(input, i);
            } else {
                i++;
            }
        }
    }

    private static int consumeNumber(byte[] input, int index) {
        int start = index;
        int length = input.length;

        if (input[index] == '-') index++;

        if (index >= length) throw JsonException.unexpectedEnd(start);

        if (input[index] == '0') {
            index++;
        } else if (input[index] >= '1' && input[index] <= '9') {
            while (index < length && Character.isDigit(input[index])) {
                index++;
            }
        } else {
            throw JsonException.invalidNumber(start);
        }

        if (index < length && input[index] == '.') {
            index++;
            if (index >= length || !Character.isDigit(input[index])) {
                throw JsonException.invalidNumber(start);
            }
            while (index < length && Character.isDigit(input[index])) index++;
        }

        if (index < length && (input[index] == 'e' || input[index] == 'E')) {
            index++;
            if (index < length && (input[index] == '+' || input[index] == '-')) {
                index++;
            }
            if (index >= length || !Character.isDigit(input[index])) {
                throw JsonException.invalidNumber(start);
            }
            while (index < length && Character.isDigit(input[index])) index++;
        }

        return index;
    }
}
