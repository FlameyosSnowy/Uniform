package me.flame.uniform.json.parser.lowlevel;

import me.flame.turboscanner.ScanResult;
import me.flame.uniform.json.exceptions.JsonException;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

/**
 * JsonCursor: Efficient low-level cursor for iterating JSON objects and fields in a byte array.
 *
 * <p>This class relies on a precomputed {@link ScanResult} which contains:
 * <ul>
 *     <li>Structural character masks (braces, brackets, commas, colons)</li>
 *     <li>Quote masks for string boundaries</li>
 *     <li>Inside-string masks for safely skipping escaped characters</li>
 * </ul>
 *
 * <p>Purpose:
 * <ul>
 *     <li>Provide a lightweight cursor API over raw JSON bytes without fully parsing into objects</li>
 *     <li>Enable high-performance JSON traversal for large buffers</li>
 *     <li>Rely on SIMD-accelerated scanning from {@link me.flame.turboscanner.VectorByteScanner}</li>
 * </ul>
 *
 * <p>Safety and correctness:
 * <ul>
 *     <li>All bounds checks are enforced using `limit`</li>
 *     <li>Uses precomputed masks to avoid rescanning bytes</li>
 *     <li>Throws {@link JsonException} for unbalanced braces or unterminated strings</li>
 * </ul>
 *
 * <p>Author: Flame
 * @version 1.0
 */
public final class JsonCursor {

    // ============================================================
    // Raw JSON input
    // ============================================================

    /** Raw byte array containing JSON to parse */
    private final byte[] input;

    /** Precomputed scan result for structural, quote, and inside-string masks */
    private final ScanResult scan;

    // ============================================================
    // Cursor state
    // ============================================================

    /** Current byte index in input */
    private int pos;

    /** Current limit (end index) for the active object/array */
    private int limit;

    // ============================================================
    // Current field metadata
    // ============================================================

    /** Start index of current field name */
    private int fieldNameStart;

    /** Length of current field name */
    private int fieldNameLen;

    /** Start index of current field value */
    private int fieldValueStart;

    /** Length of current field value */
    private int fieldValueLen;

    private int elementValueStart;
    private int elementValueLen;

    // ============================================================
    // Constructor
    // ============================================================

    /**
     * Constructs a JsonCursor over a byte array using a precomputed ScanResult.
     *
     * @param input raw JSON bytes
     * @param scan precomputed {@link ScanResult} from a VectorByteScanner
     */
    public JsonCursor(byte[] input, ScanResult scan) {
        this.input = input;
        this.scan = scan;
        this.pos = 0;
        this.limit = input.length;
    }

    private @NotNull String decodeJsonString(int start, int len) {
        int end = start + len;
        for (int i = start; i < end; i++) {
            if (input[i] == '\\') {
                return decodeJsonStringSlow(start, end);
            }
        }
        return new String(input, start, len, StandardCharsets.UTF_8);
    }

    private @NotNull String decodeJsonStringSlow(int start, int endExclusive) {
        StringBuilder sb = new StringBuilder(endExclusive - start);

        int segStart = start;
        for (int i = start; i < endExclusive; i++) {
            byte b = input[i];
            if (b != '\\') continue;

            // flush utf-8 segment before escape
            if (i > segStart) {
                sb.append(new String(input, segStart, i - segStart, StandardCharsets.UTF_8));
            }

            if (++i >= endExclusive) break;
            byte esc = input[i];
            switch (esc) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 >= endExclusive) break;
                    int code = (hex(input[i + 1]) << 12)
                        | (hex(input[i + 2]) << 8)
                        | (hex(input[i + 3]) << 4)
                        | hex(input[i + 4]);
                    i += 4;
                    sb.append((char) code);
                }
                default -> sb.append((char) (esc & 0xFF));
            }

            segStart = i + 1;
        }

        if (segStart < endExclusive) {
            sb.append(new String(input, segStart, endExclusive - segStart, StandardCharsets.UTF_8));
        }

        return sb.toString();
    }

    private static int hex(byte b) {
        if (b >= '0' && b <= '9') return b - '0';
        if (b >= 'a' && b <= 'f') return 10 + (b - 'a');
        if (b >= 'A' && b <= 'F') return 10 + (b - 'A');
        return 0;
    }

    private JsonCursor(byte[] input, ScanResult scan, int pos, int limit) {
        this.input = input;
        this.scan = scan;
        this.pos = pos;
        this.limit = limit;
    }

    // ============================================================
    // Public API: Object traversal
    // ============================================================

    /**
     * Enters a JSON object at the current position.
     * <p>Skips whitespace, checks for '{', and sets the limit to the matching '}'.
     *
     * @return true if an object was entered, false otherwise
     * @throws JsonException if braces are unbalanced
     */
    public boolean enterObject() {
        skipWhitespace();
        if (pos >= limit || input[pos] != '{') return false;

        pos++; // move past '{'
        skipWhitespace();
        return true;
    }

    // ============================================================
    // Public API: Field traversal
    // ============================================================

    /**
     * Advances the cursor to the next field in the current object.
     * Parses the field name and value, updating internal metadata.
     *
     * @return true if a next field exists, false if end of object reached
     * @throws JsonException for syntax errors (missing quotes or colons)
     */
    public boolean nextField() {
        skipWhitespace();
        if (pos >= limit) return false;
        if (input[pos] == '}') {
            pos++; // consume end of current object
            return false;
        }

        // Parse the field name
        if (input[pos] != '"') {
            throw new JsonException("Expected string field name at byte " + pos);
        }

        fieldNameStart = pos + 1;
        int endQuote = findStringEnd(pos);
        fieldNameLen = endQuote - fieldNameStart;
        pos = endQuote + 1;

        // Skip whitespace and expect ':'
        skipWhitespace();
        if (pos >= limit || input[pos] != ':') {
            throw new JsonException("Expected ':' after field name at byte " + pos);
        }
        pos++;
        skipWhitespace();

        // Parse field value
        fieldValueStart = pos;
        fieldValueLen = findValueLength(pos);
        pos += fieldValueLen;

        // Skip optional trailing comma
        skipWhitespace();
        if (pos < limit && input[pos] == ',') pos++;

        return true;
    }

    // ============================================================
    // Accessors for current field
    // ============================================================

    /** @return a ByteSlice representing the current field name */
    @Contract(" -> new")
    public @NotNull ByteSlice fieldName() {
        return new ByteSlice(input, fieldNameStart, fieldNameLen);
    }

    /** @return a ByteSlice representing the current field value */
    @Contract(" -> new")
    public @NotNull ByteSlice fieldValue() {
        return new ByteSlice(input, fieldValueStart, fieldValueLen);
    }

    public int fieldNameHash() {
        // FNV-1a 32-bit over UTF-8 bytes (works for ASCII fast path; collisions are verified with equals)
        int h = 0x811c9dc5;
        int off = fieldNameStart;
        int end = off + fieldNameLen;
        for (int i = off; i < end; i++) {
            h ^= (input[i] & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }

    public boolean fieldNameEquals(@NotNull String expected) {
        int len = expected.length();
        if (len != fieldNameLen) return false;

        int off = fieldNameStart;
        for (int i = 0; i < len; i++) {
            char c = expected.charAt(i);
            if (c > 0x7F) {
                return expected.equals(new String(input, fieldNameStart, fieldNameLen, StandardCharsets.UTF_8));
            }
            if (input[off + i] != (byte) c) return false;
        }
        return true;
    }

    public int fieldValueAsInt() {
        return parseInt(fieldValueStart, fieldValueLen);
    }

    public long fieldValueAsLong() {
        return parseLong(fieldValueStart, fieldValueLen);
    }

    public boolean fieldValueAsBoolean() {
        // true/false are always ascii; avoid allocation
        if (fieldValueLen == 4
            && input[fieldValueStart] == 't'
            && input[fieldValueStart + 1] == 'r'
            && input[fieldValueStart + 2] == 'u'
            && input[fieldValueStart + 3] == 'e') return true;
        if (fieldValueLen == 5
            && input[fieldValueStart] == 'f'
            && input[fieldValueStart + 1] == 'a'
            && input[fieldValueStart + 2] == 'l'
            && input[fieldValueStart + 3] == 's'
            && input[fieldValueStart + 4] == 'e') return false;
        return Boolean.parseBoolean(fieldValue().toString());
    }

    public @NotNull String fieldValueAsUnquotedString() {
        if (fieldValueLen >= 2 && input[fieldValueStart] == '"' && input[fieldValueStart + fieldValueLen - 1] == '"') {
            int start = fieldValueStart + 1;
            int len = fieldValueLen - 2;
            return decodeJsonString(start, len);
        }
        return new String(input, fieldValueStart, fieldValueLen, StandardCharsets.UTF_8);
    }

    public boolean enterArray() {
        skipWhitespace();
        if (pos >= limit || input[pos] != '[') return false;
        pos++; // past '['
        skipWhitespace();
        return true;
    }

    public boolean nextElement() {
        skipWhitespace();
        if (pos >= limit) return false;
        if (input[pos] == ']') {
            pos++; // consume end of array
            return false;
        }

        elementValueStart = pos;
        elementValueLen = findValueLength(pos);
        pos += elementValueLen;

        skipWhitespace();
        if (pos < limit && input[pos] == ',') pos++;
        return true;
    }

    @Contract(" -> new")
    public @NotNull ByteSlice elementValue() {
        return new ByteSlice(input, elementValueStart, elementValueLen);
    }

    public @NotNull JsonCursor elementValueCursor() {
        return new JsonCursor(input, scan, elementValueStart, elementValueStart + elementValueLen);
    }

    public @NotNull String elementValueAsUnquotedString() {
        if (elementValueLen >= 2 && input[elementValueStart] == '"' && input[elementValueStart + elementValueLen - 1] == '"') {
            int start = elementValueStart + 1;
            int len = elementValueLen - 2;
            return decodeJsonString(start, len);
        }
        return new String(input, elementValueStart, elementValueLen, StandardCharsets.UTF_8);
    }

    public @NotNull JsonCursor fieldValueCursor() {
        return new JsonCursor(input, scan, fieldValueStart, fieldValueStart + fieldValueLen);
    }

    // ============================================================
    // Internal helpers: String and value parsing
    // ============================================================

    /**
     * Finds the index of the ending quote for a string, handling escape sequences.
     * <p>Uses the precomputed quote and inside-string masks for efficiency.
     *
     * @param startQuote index of the opening quote
     * @return index of the closing quote
     * @throws JsonException if string is unterminated
     */
    private int findStringEnd(int startQuote) {
        int from = startQuote + 1;
        int word = from >>> 6;
        int bit = from & 63;

        long[] quotes = scan.getQuoteMask();
        int lanes = quotes.length;

        if (lanes == 0) {
            return findStringEndManual(startQuote);
        }

        long mask = (word < lanes) ? quotes[word] & (~0L << bit) : 0;

        while (word < lanes) {
            while (mask != 0) {
                int offset = Long.numberOfTrailingZeros(mask);
                int q = (word << 6) + offset;

                // Ensure quote is not escaped
                if (q + 1 >= input.length || !scan.isInsideString(q + 1)) {
                    return q;
                }

                mask &= mask - 1; // clear the lowest set bit
            }

            word++;
            if (word < lanes) mask = quotes[word];
        }

        return findStringEndManual(startQuote);
    }

    private int findStringEndManual(int startQuote) {
        boolean escaped = false;
        for (int i = startQuote + 1; i < limit; i++) {
            byte b = input[i];
            if (escaped) {
                escaped = false;
                continue;
            }
            if (b == '\\') {
                escaped = true;
                continue;
            }
            if (b == '"') return i;
        }
        throw new JsonException("Unterminated string starting at byte " + startQuote);
    }

    /**
     * Determines the length of a JSON value starting at a given position.
     *
     * <p>Handles:
     * <ul>
     *     <li>Strings</li>
     *     <li>Objects</li>
     *     <li>Arrays</li>
     *     <li>Numbers, true, false, null (until next structural)</li>
     * </ul>
     *
     * @param start starting index
     * @return length in bytes of the value
     */
    private int findValueLength(int start) {
        int endExclusive = skipValueEndExclusive(start);
        return endExclusive - start;
    }

    private int skipValueEndExclusive(int start) {
        int i = start;
        if (i >= limit) return i;

        byte b = input[i];
        if (b == '"') {
            int end = findStringEnd(i);
            return end + 1;
        }

        int depthObj = 0;
        int depthArr = 0;
        boolean inString = false;
        boolean escaped = false;

        for (; i < limit; i++) {
            byte c = input[i];

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (c == '\\') {
                    escaped = true;
                    continue;
                }
                if (c == '"') {
                    inString = false;
                }
                continue;
            }

            switch (c) {
                case '"' -> inString = true;
                case '{' -> depthObj++;
                case '}' -> {
                    if (depthObj == 0 && depthArr == 0) return i;
                    depthObj--;
                    if (depthObj < 0) return i;
                }
                case '[' -> depthArr++;
                case ']' -> {
                    if (depthObj == 0 && depthArr == 0) return i;
                    depthArr--;
                    if (depthArr < 0) return i;
                }
                case ',' -> {
                    if (depthObj == 0 && depthArr == 0) return i;
                }
                default -> {
                    // continue
                }
            }

            // If the value is a scalar, stop when the next delimiter arrives.
            if (depthObj == 0 && depthArr == 0) {
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') return i;
            }
        }

        return limit;
    }

    private int parseInt(int start, int len) {
        int i = start;
        int end = start + len;
        boolean neg = false;
        if (i < end && input[i] == '-') {
            neg = true;
            i++;
        }
        int val = 0;
        while (i < end) {
            byte b = input[i++];
            int d = b - '0';
            if (d < 0 || d > 9) throw new NumberFormatException("Invalid int");
            val = val * 10 + d;
        }
        return neg ? -val : val;
    }

    private long parseLong(int start, int len) {
        int i = start;
        int end = start + len;
        boolean neg = false;
        if (i < end && input[i] == '-') {
            neg = true;
            i++;
        }
        long val = 0;
        while (i < end) {
            byte b = input[i++];
            int d = b - '0';
            if (d < 0 || d > 9) throw new NumberFormatException("Invalid long");
            val = val * 10 + d;
        }
        return neg ? -val : val;
    }

    // ============================================================
    // Internal helpers: Structural traversal
    // ============================================================

    /**
     * Finds the index of the matching closing brace for an object.
     * <p>Traverses the precomputed structural mask for efficiency.
     *
     * @param openPos index of opening '{'
     * @return index of matching '}'
     * @throws JsonException if braces are unbalanced
     */
    private int findMatchingBrace(int openPos) {
        int depth = 1;
        int word = (openPos + 1) >>> 6;
        long[] structural = scan.getStructuralMask();
        int lanes = structural.length;

        if (lanes == 0) {
            return findMatchingBraceManual(openPos);
        }

        long mask = (word < lanes) ? structural[word] & (~0L << ((openPos + 1) & 63)) : 0;

        while (word < lanes) {
            while (mask != 0) {
                int offset = Long.numberOfTrailingZeros(mask);
                int idx = (word << 6) + offset;

                byte b = input[idx];
                if (b == '{') depth++;
                else if (b == '}') {
                    depth--;
                    if (depth == 0) return idx;
                }

                mask &= mask - 1;
            }

            word++;
            if (word < lanes) mask = structural[word];
        }

        return findMatchingBraceManual(openPos);
    }

    private int findMatchingBraceManual(int openPos) {
        int depth = 1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = openPos + 1; i < limit; i++) {
            byte b = input[i];

            if (inString) {
                if (escaped) {
                    escaped = false;
                    continue;
                }
                if (b == '\\') {
                    escaped = true;
                    continue;
                }
                if (b == '"') {
                    inString = false;
                }
                continue;
            }

            if (b == '"') {
                inString = true;
                continue;
            }

            if (b == '{') depth++;
            else if (b == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }

        throw new JsonException("Unbalanced braces starting at byte " + openPos);
    }

    // ============================================================
    // Internal helpers: whitespace
    // ============================================================

    /** Skips whitespace characters (space, newline, carriage return, tab) */
    private void skipWhitespace() {
        while (pos < limit) {
            byte b = input[pos];
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') pos++;
            else break;
        }
    }
}