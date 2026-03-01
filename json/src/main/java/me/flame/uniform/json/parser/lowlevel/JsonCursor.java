package me.flame.uniform.json.parser.lowlevel;

import me.flame.turboscanner.ScanResult;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.dom.JsonArray;
import me.flame.uniform.json.dom.JsonBoolean;
import me.flame.uniform.json.dom.JsonByte;
import me.flame.uniform.json.dom.JsonDouble;
import me.flame.uniform.json.dom.JsonFloat;
import me.flame.uniform.json.dom.JsonInteger;
import me.flame.uniform.json.dom.JsonLong;
import me.flame.uniform.json.dom.JsonNull;
import me.flame.uniform.json.dom.JsonNumber;
import me.flame.uniform.json.dom.JsonObject;
import me.flame.uniform.json.dom.JsonShort;
import me.flame.uniform.json.dom.JsonString;
import me.flame.uniform.json.dom.JsonValue;
import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.features.JsonReadFeature;
import me.flame.uniform.json.parser.JsonReadCursor;
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
 * @version 2.0
 */
public final class JsonCursor implements JsonReadCursor {

    // ============================================================
    // Static lookup tables
    // ============================================================

    /** true = byte is plain ASCII whitespace (space/tab/CR/LF) */
    private static final boolean[] WS = new boolean[256];


    /** Hex digit value, or -1 for non-hex. */
    private static final int[] HEX_VAL = new int[256];

    /** Non-NaN/Infinity number start chars. */
    private static final boolean[] NUM_START = new boolean[256];

    static {
        WS[' ']  = true;
        WS['\t'] = true;
        WS['\r'] = true;
        WS['\n'] = true;

        for (int i = 0; i < 256; i++) HEX_VAL[i] = -1;
        for (int i = '0'; i <= '9'; i++) HEX_VAL[i] = i - '0';
        for (int i = 'a'; i <= 'f'; i++) HEX_VAL[i] = 10 + i - 'a';
        for (int i = 'A'; i <= 'F'; i++) HEX_VAL[i] = 10 + i - 'A';

        for (int i = '0'; i <= '9'; i++) NUM_START[i] = true;
        NUM_START['-'] = true;
    }

    private static final byte[] BYTES_NAN     = {'N', 'a', 'N'};
    private static final byte[] BYTES_INF     = {'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};
    private static final byte[] BYTES_NEG_INF = {'-', 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};

    // POW10: extended to ±22 - covers virtually all real-world JSON doubles
    // Index 0 = 1e-22, index 22 = 1e0, index 44 = 1e22
    private static final double[] POW10 = new double[45];
    private static final int POW10_OFFSET = 22;

    static {
        for (int i = 0; i < POW10.length; i++) POW10[i] = Math.pow(10, i - POW10_OFFSET);
    }

    // Thread-local decode buffer - avoids repeated allocation in string slow path
    private static final ThreadLocal<byte[]> DECODE_BUFFER = ThreadLocal.withInitial(() -> new byte[1024]);

    // SWAR constants
    // These operate on 8 bytes packed into a long (little-endian).
    private static final long SWAR_01 = 0x0101010101010101L; // 72340172838076673
    private static final long SWAR_80 = 0x8080808080808080L; // 9259542123273814144
    // Broadcast of '\\' (0x5C) and 0x20 (space) for SWAR scanning
    private static final long SWAR_BACKSLASH = 0x5C5C5C5C5C5C5C5CL; // 6655295901103053916

    // ============================================================
    // Instance state
    // ============================================================

    private final byte[]     input;
    private final ScanResult scan;
    private       int        pos;
    private final int        limit;

    private int fieldNameStart;
    private int fieldNameLen;
    private int fieldValueStart;
    private int fieldValueLen;
    private int elementValueStart;
    private int elementValueLen;

    private final boolean allowJavaComments;
    private final boolean allowYamlComments;
    private final boolean allowSingleQuotes;
    private final boolean allowUnquotedFieldNames;
    private final boolean allowUnescapedControlChars;
    private final boolean allowBackslashEscapingAny;
    private final boolean allowLeadingZeros;
    private final boolean allowNonNumericNumbers;
    private final boolean allowMissingValues;
    private final boolean allowTrailingComma;
    private final boolean strictDuplicateDetection;
    private final boolean ignoreUndefined;
    private final boolean wrapExceptions;
    private final boolean anyComments;

    public JsonCursor(byte[] input, ScanResult scan, JsonConfig config) {
        this.input = input;
        this.scan  = scan;
        this.pos   = 0;
        this.limit = input.length;

        this.allowJavaComments          = config.hasReadFeature(JsonReadFeature.ALLOW_JAVA_COMMENTS) || JsonReadFeature.ALLOW_JAVA_COMMENTS.isDefaultValue();
        this.allowYamlComments          = config.hasReadFeature(JsonReadFeature.ALLOW_YAML_COMMENTS) || JsonReadFeature.ALLOW_YAML_COMMENTS.isDefaultValue();
        this.allowSingleQuotes          = config.hasReadFeature(JsonReadFeature.ALLOW_SINGLE_QUOTES) || JsonReadFeature.ALLOW_SINGLE_QUOTES.isDefaultValue();
        this.allowUnquotedFieldNames    = config.hasReadFeature(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES) || JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.isDefaultValue();
        this.allowUnescapedControlChars = config.hasReadFeature(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS) || JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.isDefaultValue();
        this.allowBackslashEscapingAny  = config.hasReadFeature(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER) || JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.isDefaultValue();
        this.allowLeadingZeros          = config.hasReadFeature(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS) || JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.isDefaultValue();
        this.allowNonNumericNumbers     = config.hasReadFeature(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS) || JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.isDefaultValue();
        this.allowMissingValues         = config.hasReadFeature(JsonReadFeature.ALLOW_MISSING_VALUES) || JsonReadFeature.ALLOW_MISSING_VALUES.isDefaultValue();
        this.allowTrailingComma         = config.hasReadFeature(JsonReadFeature.ALLOW_TRAILING_COMMA) || JsonReadFeature.ALLOW_TRAILING_COMMA.isDefaultValue();
        this.strictDuplicateDetection   = config.hasReadFeature(JsonReadFeature.STRICT_DUPLICATE_DETECTION) || JsonReadFeature.STRICT_DUPLICATE_DETECTION.isDefaultValue();
        this.ignoreUndefined            = config.hasReadFeature(JsonReadFeature.IGNORE_UNDEFINED) || JsonReadFeature.IGNORE_UNDEFINED.isDefaultValue();
        this.wrapExceptions             = config.hasReadFeature(JsonReadFeature.WRAP_EXCEPTIONS) || JsonReadFeature.WRAP_EXCEPTIONS.isDefaultValue();
        this.anyComments = allowJavaComments || allowYamlComments;
    }

    /** Private sub-cursor constructor - copies flags by value, no config lookup. */
    private JsonCursor(byte[] input, ScanResult scan, int pos, int limit, JsonCursor p) {
        this.input = input; this.scan = scan; this.pos = pos; this.limit = limit;
        this.allowJavaComments          = p.allowJavaComments;
        this.allowYamlComments          = p.allowYamlComments;
        this.allowSingleQuotes          = p.allowSingleQuotes;
        this.allowUnquotedFieldNames    = p.allowUnquotedFieldNames;
        this.allowUnescapedControlChars = p.allowUnescapedControlChars;
        this.allowBackslashEscapingAny  = p.allowBackslashEscapingAny;
        this.allowLeadingZeros          = p.allowLeadingZeros;
        this.allowNonNumericNumbers     = p.allowNonNumericNumbers;
        this.allowMissingValues         = p.allowMissingValues;
        this.allowTrailingComma         = p.allowTrailingComma;
        this.strictDuplicateDetection   = p.strictDuplicateDetection;
        this.ignoreUndefined            = p.ignoreUndefined;
        this.wrapExceptions             = p.wrapExceptions;
        this.anyComments                = p.anyComments;
    }

    // ============================================================
    // Object / Array navigation
    // ============================================================
    @Override
    public boolean enterObject() {
        skipWs();
        if (pos >= limit || input[pos] != '{') return false;
        pos++;
        skipWs();
        return true;
    }

    @Override
    public boolean nextField() {
        skipWs();
        if (pos >= limit) return false;

        byte b = input[pos];

        if (b == '}') { pos++; return false; }

        if (allowTrailingComma && b == ',') {
            pos++;
            skipWs();
            if (pos < limit && input[pos] == '}') { pos++; return false; }
            b = pos < limit ? input[pos] : 0;
        }

        if (b == '"') {
            fieldNameStart = pos + 1;
            int eq = findStringEnd(pos);
            fieldNameLen   = eq - fieldNameStart;
            pos            = eq + 1;
        } else if (allowSingleQuotes && b == '\'') {
            fieldNameStart = pos + 1;
            int eq = findStringEndManual(pos, (byte) '\'');
            fieldNameLen   = eq - fieldNameStart;
            pos            = eq + 1;
        } else if (allowUnquotedFieldNames) {
            fieldNameStart = pos;
            final byte[] inp = input;
            final int    lim = limit;
            while (pos < lim) {
                byte c = inp[pos];
                if (c == ':' || WS[c & 0xFF]) break;
                pos++;
            }
            fieldNameLen = pos - fieldNameStart;
        } else {
            throw error("Expected field name at byte " + pos);
        }

        skipWs();
        if (pos >= limit || input[pos] != ':') throw error("Expected ':' at byte " + pos);
        pos++;
        skipWs();

        if (allowMissingValues && pos < limit
            && (input[pos] == ',' || input[pos] == '}')) {
            fieldValueStart = pos;
            fieldValueLen   = 0;
        } else {
            fieldValueStart = pos;
            fieldValueLen   = findValueLength(pos);
            pos            += fieldValueLen;
        }

        skipWs();
        if (pos < limit && input[pos] == ',') pos++;
        return true;
    }

    @Override
    public boolean enterArray() {
        skipWs();
        if (pos >= limit || input[pos] != '[') return false;
        pos++;
        skipWs();
        return true;
    }

    @Override
    public boolean nextElement() {
        skipWs();
        final int limit = this.limit;
        if (pos >= limit) return false;

        byte b = input[pos];

        if (b == ']') { pos++; return false; }

        if (allowTrailingComma && b == ',') {
            pos++;
            skipWs();
            if (pos < limit && input[pos] == ']') { pos++; return false; }
        }

        if (allowMissingValues && input[pos] == ',') {
            elementValueStart = pos;
            elementValueLen   = 0;
            return true;
        }

        elementValueStart = pos;
        elementValueLen   = findValueLength(pos);
        pos              += elementValueLen;

        skipWs();
        if (pos < limit && input[pos] == ',') pos++;
        return true;
    }

    // ============================================================
    // Field name access
    // ============================================================
    @Override
    @Contract(" -> new")
    public @NotNull ByteSlice fieldName() {
        return new ByteSlice(input, fieldNameStart, fieldNameLen);
    }

    /**
     * Returns the current field name as a String without allocating a ByteSlice.
     * Saves one heap allocation vs fieldName().toString() in codegen-heavy paths.
     */
    @Override
    public @NotNull String  fieldNameAsString() {
        return new String(input, fieldNameStart, fieldNameLen, StandardCharsets.UTF_8);
    }

    @Override
    public int fieldNameHash() {
        int h   = 0x811c9dc5;
        final byte[] inp = input;
        final int end    = fieldNameStart + fieldNameLen;
        for (int i = fieldNameStart; i < end; i++) {
            h ^= inp[i] & 0xFF;
            h *= 0x01000193;
        }
        return h;
    }

    @Override
    public boolean fieldNameEquals(@NotNull String expected) {
        final int len = expected.length();
        if (len != fieldNameLen) return false;
        final byte[] inp = input;
        final int    off = fieldNameStart;
        for (int i = 0; i < len; i++) {
            char c = expected.charAt(i);
            if (c > 0x7F) return expected.equals(new String(inp, off, len, StandardCharsets.UTF_8));
            if (inp[off + i] != (byte) c) return false;
        }
        return true;
    }

    // ============================================================
    // Field value access
    // ============================================================

    @Contract(" -> new")
    @Override
    public @NotNull ByteSlice fieldValue() {
        return new ByteSlice(input, fieldValueStart, fieldValueLen);
    }

    @Override
    public int     fieldValueAsInt()    { return parseInt(fieldValueStart, fieldValueLen); }

    @Override
    public long    fieldValueAsLong()   { return parseLong(fieldValueStart, fieldValueLen); }

    @Override
    public double  fieldValueAsDouble() { return parseDouble(fieldValueStart, fieldValueLen); }

    @Override
    public float   fieldValueAsFloat()  { return (float) parseDouble(fieldValueStart, fieldValueLen); }

    @Override
    public short   fieldValueAsShort()  { return (short) parseInt(fieldValueStart, fieldValueLen); }

    @Override
    public byte    fieldValueAsByte()   { return (byte)  parseInt(fieldValueStart, fieldValueLen); }

    @Override
    public boolean fieldValueAsBoolean() {
        final byte[] inp = input;
        final int    s   = fieldValueStart;
        final int    len = fieldValueLen;
        if (len == 4 && inp[s]=='t' && inp[s+1]=='r' && inp[s+2]=='u' && inp[s+3]=='e') return true;
        if (len == 5 && inp[s]=='f' && inp[s+1]=='a' && inp[s+2]=='l' && inp[s+3]=='s' && inp[s+4]=='e') return false;
        if (len == 4) {
            byte b0=inp[s], b1=inp[s+1], b2=inp[s+2], b3=inp[s+3];
            if ((b0=='t'||b0=='T') && (b1=='r'||b1=='R') && (b2=='u'||b2=='U') && (b3=='e'||b3=='E')) return true;
        }
        if (len == 5) {
            byte b0=inp[s], b1=inp[s+1], b2=inp[s+2], b3=inp[s+3], b4=inp[s+4];
            if ((b0=='f'||b0=='F') && (b1=='a'||b1=='A') && (b2=='l'||b2=='L') && (b3=='s'||b3=='S') && (b4=='e'||b4=='E')) return false;
        }
        return "true".equalsIgnoreCase(new String(inp, s, len, StandardCharsets.UTF_8));
    }

    @Override
    public @NotNull String fieldValueAsUnquotedString() {
        final int s   = fieldValueStart;
        final int len = fieldValueLen;
        if (len >= 2 && input[s] == '"'  && input[s + len - 1] == '"')  return decodeJsonString(s + 1, len - 2);
        if (allowSingleQuotes && len >= 2
            && input[s] == '\'' && input[s + len - 1] == '\'') return decodeJsonString(s + 1, len - 2);
        return new String(input, s, len, StandardCharsets.UTF_8);
    }

    @Override
    public @NotNull JsonCursor fieldValueCursor() {
        return new JsonCursor(input, scan, fieldValueStart, fieldValueStart + fieldValueLen, this);
    }

    @Override
    @Contract(" -> new")
    public @NotNull ByteSlice elementValue() {
        return new ByteSlice(input, elementValueStart, elementValueLen);
    }

    /**
     * Direct element value accessors - each eliminates one ByteSlice allocation
     * and one intermediate String allocation compared to elementValue().toString().
     * These are the methods emitted by codegen for primitive array/collection elements.
     */
    @Override
    public int     elementValueAsInt()     { return parseInt(elementValueStart, elementValueLen); }

    @Override
    public long    elementValueAsLong()    { return parseLong(elementValueStart, elementValueLen); }

    @Override
    public double  elementValueAsDouble()  { return parseDouble(elementValueStart, elementValueLen); }

    @Override
    public float   elementValueAsFloat()   { return (float) parseDouble(elementValueStart, elementValueLen); }

    @Override
    public short   elementValueAsShort()   { return (short) parseInt(elementValueStart, elementValueLen); }

    @Override
    public byte    elementValueAsByte()    { return (byte)  parseInt(elementValueStart, elementValueLen); }

    @Override
    public boolean elementValueAsBoolean() {
        final byte[] inp = input;
        final int    s   = elementValueStart;
        final int    len = elementValueLen;
        if (len == 4 && inp[s]=='t' && inp[s+1]=='r' && inp[s+2]=='u' && inp[s+3]=='e') return true;
        if (len == 5 && inp[s]=='f' && inp[s+1]=='a' && inp[s+2]=='l' && inp[s+3]=='s' && inp[s+4]=='e') return false;
        return "true".equalsIgnoreCase(new String(inp, s, len, StandardCharsets.UTF_8));
    }

    @Override
    public @NotNull String elementValueAsUnquotedString() {
        return elementValueAsUnquotedString(elementValueStart, elementValueLen);
    }

    public @NotNull String elementValueAsUnquotedString(int s, int len) {
        if (len >= 2 && input[s] == '"'  && input[s + len - 1] == '"') {
            String decodedString = decodeJsonString(s + 1, len - 2);
            return decodedString;
        }
        if (allowSingleQuotes && len >= 2 && input[s] == '\'' && input[s + len - 1] == '\'') {
            String decodedString = decodeJsonString(s + 1, len - 2);
            return decodedString;
        }
        String decodedString = new String(input, s, len, StandardCharsets.UTF_8);
        return decodedString;
    }

    @Override
    public @NotNull JsonCursor elementValueCursor() {
        return new JsonCursor(input, scan, elementValueStart, elementValueStart + elementValueLen, this);
    }

    // ============================================================
    // Whitespace / comment skipping
    // ============================================================

    /**
     * Fast path: pure whitespace skip using the lookup table.
     * Comment handling pushed into a cold method so JIT can inline and unroll this freely.
     */
    private void skipWs() {
        final byte[] inp = input;
        final int    lim = limit;
        int p = pos;
        while (p < lim && WS[inp[p] & 0xFF]) {
            p++;
        }
        pos = p;
        if (anyComments) {
            skipComments();
        }
    }

    private void skipComments() {
        final byte[]          inp = input;
        final int             lim = limit;
        int                   pos = this.pos;
        boolean allowJavaComments = this.allowJavaComments;
        boolean allowYamlComments = this.allowYamlComments;
        boolean again = true;
        while (again) {
            again = false;
            while (pos < lim && WS[inp[pos] & 0xFF]) {
                pos++;
            }

            if (allowJavaComments && pos + 1 < lim && inp[pos] == '/') {
                if (inp[pos + 1] == '/') {
                    pos += 2;
                    while (pos < lim && inp[pos] != '\n') {
                        pos++;
                    }
                    again = true;
                } else if (inp[pos + 1] == '*') {
                    pos += 2;
                    while (pos + 1 < lim && !(inp[pos] == '*' && inp[pos + 1] == '/')) {
                        pos++;
                    }
                    if (pos + 1 < lim) {
                        pos += 2;
                    }
                    again = true;
                }
            }

            if (allowYamlComments && pos < lim && inp[pos] == '#') {
                do {
                    pos++;
                } while (pos < lim && inp[pos] != '\n');
                again = true;
            }
        }

        this.pos = pos;
    }

    // ============================================================
    // String decoding
    // ============================================================

    /**
     * Fast path string decoder.
     * Uses SWAR (SIMD Within A Register) to scan 8 bytes at once for backslash
     * or control characters. For typical ASCII JSON strings with no escapes this
     * is ~8x faster than the previous byte-by-byte scan before falling through to
     * new String(). The slow path is only entered when a backslash is actually found.
     */
    private @NotNull String decodeJsonString(int start, int len) {
        final byte[] inp  = input;
        final int    end  = start + len;
        final boolean checkCtrl = !allowUnescapedControlChars;

        int i = start;

        // SWAR: scan 8 bytes at a time
        final int limit8 = end - 7;
        while (i < limit8) {
            final long word = readLongLE(inp, i);
            if (swarHasBackslash(word) != 0)               return decodeJsonStringSlow(start, end);
            if (checkCtrl && swarHasLessThan(word, 0x20) != 0)
                throw error("Unescaped control character near byte " + i);
            i += 8;
        }

        // Remaining 1–7 bytes
        while (i < end) {
            final int b = inp[i] & 0xFF;
            if (b == '\\') return decodeJsonStringSlow(start, end);
            if (checkCtrl && b < 0x20) throw error("Unescaped control character at byte " + i);
            i++;
        }

        return new String(inp, start, len, StandardCharsets.UTF_8);
    }

    private @NotNull String decodeJsonStringSlow(int start, int endExclusive) {
        final byte[] inp = input;
        final int    len = endExclusive - start;

        byte[] buf = DECODE_BUFFER.get();
        if (buf.length < len) {
            buf = new byte[Math.max(len, buf.length * 2)];
            DECODE_BUFFER.set(buf);
        }
        int out = 0;

        int i = start;
        while (i < endExclusive) {
            final int b = inp[i] & 0xFF;

            if (!allowUnescapedControlChars && b < 0x20)
                throw error("Unescaped control character at byte " + i);

            if (b != '\\') {
                // ── SWAR bulk copy: find the next backslash in 8-byte strides ──────
                int j = i + 1;
                final int bulk8 = endExclusive - 7;
                final boolean checkCtrl = !allowUnescapedControlChars;

                while (j < bulk8) {
                    final long word = readLongLE(inp, j);
                    final long hits = swarHasBackslash(word);
                    if (hits != 0) {
                        // Locate exact byte offset within the word
                        j += Long.numberOfTrailingZeros(hits) >>> 3;
                        break;
                    }
                    if (checkCtrl && swarHasLessThan(word, 0x20) != 0)
                        throw error("Unescaped control character near byte " + j);
                    j += 8;
                }

                // Byte-precise scan for the remainder (< 8 bytes or after SWAR hit)
                while (j < endExclusive) {
                    final int c = inp[j] & 0xFF;
                    if (checkCtrl && c < 0x20) throw error("Unescaped control character at byte " + j);
                    if (c == '\\') break;
                    j++;
                }

                // Bulk copy [i, j) -> single JVM arraycopy intrinsic
                final int copyLen = j - i;
                if (out + copyLen > buf.length) {
                    buf = growBuffer(buf, out, out + copyLen);
                    DECODE_BUFFER.set(buf);
                }
                System.arraycopy(inp, i, buf, out, copyLen);
                out += copyLen;
                i = j;
                continue;
            }

            // Escape sequence
            if (++i >= endExclusive) break;
            final byte esc = inp[i++];

            switch (esc) {
                case '"'  -> buf[out++] = '"';
                case '\'' -> {
                    if (allowSingleQuotes || allowBackslashEscapingAny) buf[out++] = '\'';
                    else throw error("Invalid escape \\' at byte " + (i - 1));
                }
                case '\\' -> buf[out++] = '\\';
                case '/'  -> buf[out++] = '/';
                case 'b'  -> buf[out++] = '\b';
                case 'f'  -> buf[out++] = '\f';
                case 'n'  -> buf[out++] = '\n';
                case 'r'  -> buf[out++] = '\r';
                case 't'  -> buf[out++] = '\t';
                case 'u'  -> {
                    if (i + 3 >= endExclusive) break;
                    final int h1 = HEX_VAL[inp[i]     & 0xFF];
                    final int h2 = HEX_VAL[inp[i + 1] & 0xFF];
                    final int h3 = HEX_VAL[inp[i + 2] & 0xFF];
                    final int h4 = HEX_VAL[inp[i + 3] & 0xFF];
                    if ((h1 | h2 | h3 | h4) < 0) throw error("Invalid \\u escape at byte " + (i - 2));
                    i += 4;

                    int cp = (h1 << 12) | (h2 << 8) | (h3 << 4) | h4;

                    // Surrogate pair
                    if (cp >= 0xD800 && cp <= 0xDBFF && i + 5 < endExclusive
                        && inp[i] == '\\' && inp[i + 1] == 'u') {
                        final int l1 = HEX_VAL[inp[i + 2] & 0xFF];
                        final int l2 = HEX_VAL[inp[i + 3] & 0xFF];
                        final int l3 = HEX_VAL[inp[i + 4] & 0xFF];
                        final int l4 = HEX_VAL[inp[i + 5] & 0xFF];
                        if ((l1 | l2 | l3 | l4) >= 0) {
                            final int low = (l1 << 12) | (l2 << 8) | (l3 << 4) | l4;
                            if (low >= 0xDC00 && low <= 0xDFFF) {
                                cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
                                i += 6;
                            }
                        }
                    }

                    if (out + 4 > buf.length) {
                        buf = growBuffer(buf, out, out + 4);
                        DECODE_BUFFER.set(buf);
                    }
                    out = writeUtf8(cp, buf, out);
                }
                default -> {
                    if (allowBackslashEscapingAny) buf[out++] = esc;
                    else throw error("Invalid escape \\" + (char) esc + " at byte " + (i - 1));
                }
            }
        }

        return new String(buf, 0, out, StandardCharsets.UTF_8);
    }

    // ============================================================
    // String-finding internals
    // ============================================================
    private int findStringEnd(int startQuote) {
        final int from  = startQuote + 1;
        final long[] quotes = scan.getQuoteMask();
        final int    lanes  = quotes.length;

        if (lanes == 0) return findStringEndManual(startQuote, (byte) '"');

        int  word = from >>> 6;
        long mask = (word < lanes) ? quotes[word] & (~0L << (from & 63)) : 0L;

        while (word < lanes) {
            while (mask != 0L) {
                final int q = (word << 6) + Long.numberOfTrailingZeros(mask);
                if (q + 1 >= input.length || !scan.isInsideString(q + 1)) return q;
                mask &= mask - 1;
            }
            if (++word < lanes) mask = quotes[word];
        }
        return findStringEndManual(startQuote, (byte) '"');
    }

    private int findStringEndManual(int startQuote, byte closing) {
        final byte[] inp = input;
        final int    lim = limit;
        boolean escaped  = false;
        for (int i = startQuote + 1; i < lim; i++) {
            final byte b = inp[i];
            if (escaped)      { escaped = false; continue; }
            if (b == '\\')    { escaped = true;  continue; }
            if (b == closing) return i;
        }
        throw error("Unterminated string at byte " + startQuote);
    }

    private int findValueLength(int start) {
        return skipValueEnd(start) - start;
    }

    private int skipValueEnd(int start) {
        if (start >= limit) return start;

        final byte first = input[start];

        if (first == '"')                       return findStringEnd(start) + 1;
        if (allowSingleQuotes && first == '\'') return findStringEndManual(start, (byte) '\'') + 1;

        final long[] structural = scan.getStructuralMask();
        final int    lanes      = structural.length;
        if (lanes == 0) return skipValueEndScalar(start);

        // Depth counters for nested JSON structures
        int depthObj = 0, depthArr = 0;

        // Determine which 64-bit lane (word) the start index belongs to.
        // >>> 6 is equivalent to start / 64, but as a logical shift.
        // Logical shift ensures zero-fill and avoids sign propagation.
        int word = start >>> 6;

        // Build initial mask for this 64-bit lane.
        // If word is within bounds:
        //   - structural[word] contains bit flags for structural characters.
        //   - (~0L << (start & 63)) clears bits before the starting offset inside the lane.
        // If word is out of bounds, mask = 0 to skip processing.
        long mask = (word < lanes)
            ? structural[word] & (~0L << (start & 63))
            : 0L;

        while (word < lanes) {
            // While there are structural characters left in this lane
            while (mask != 0L) {
                // Find index of next structural character.
                // numberOfTrailingZeros finds position of lowest set bit.
                final int idx = (word << 6) + Long.numberOfTrailingZeros(mask);

                if (idx >= limit) return limit;

                switch (input[idx]) {
                    // Opening object brace
                    case '{' -> depthObj++;

                    // Closing object brace
                    case '}' -> {
                        // If at top level, this closes the structure.
                        if (depthObj == 0 && depthArr == 0) return idx;

                        // Decrement depth; if negative, unbalanced close.
                        if (--depthObj < 0) return idx;
                    }

                    // Opening array bracket
                    case '[' -> depthArr++;

                    // Closing array bracket
                    case ']' -> {
                        // If at top level, this closes the structure.
                        if (depthObj == 0 && depthArr == 0) return idx;

                        // Decrement depth; if negative, unbalanced close.
                        if (--depthArr < 0) return idx;
                    }

                    // Comma at top level separates values
                    case ',' -> {
                        if (depthObj == 0 && depthArr == 0) return idx;
                    }
                }

                // Clear the lowest set bit to move to next structural character.
                // Classic bit trick: removes lowest 1-bit.
                mask &= mask - 1;
            }

            // Move to next 64-bit lane
            if (++word < lanes)
                mask = structural[word];
        }

        // If nothing found before limit, return limit.
        return limit;
    }

    private int skipValueEndScalar(int start) {
        final byte[] inp = input;
        final int    lim = limit;
        int     depthObj = 0, depthArr = 0;
        boolean inStr    = false, escaped = false;
        byte    strClose = '"';

        for (int i = start; i < lim; i++) {
            final byte c = inp[i];
            if (inStr) {
                if (escaped)      { escaped = false; continue; }
                if (c == '\\')    { escaped = true;  continue; }
                if (c == strClose){ inStr   = false;  continue; }
                continue;
            }
            if (c == '"') { inStr = true; strClose = '"'; continue; }
            if (allowSingleQuotes && c == '\'') { inStr = true; strClose = '\''; continue; }
            switch (c) {
                case '{' -> depthObj++;
                case '}' -> { if (depthObj == 0 && depthArr == 0) return i; depthObj--; }
                case '[' -> depthArr++;
                case ']' -> { if (depthObj == 0 && depthArr == 0) return i; depthArr--; }
                case ',' -> { if (depthObj == 0 && depthArr == 0) return i; }
            }
            if (depthObj == 0 && depthArr == 0 && WS[c & 0xFF]) return i;
        }
        return lim;
    }

    // ============================================================
    // Number parsing
    // ============================================================

    private int parseInt(final int start, final int len) {
        if (len == 0) return 0;
        final byte[] inp = input;

        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN) || matchBytes(start, len, BYTES_INF)) return 0;
            if (matchBytes(start, len, BYTES_NEG_INF)) return 0;
        }

        int i   = start;
        int end = start + len;
        boolean neg = false;
        if (inp[i] == '-') { neg = true; i++; }

        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);

        // Unrolled for the common 1–4 digit case to help JIT
        int val = 0;
        return Math.toIntExact(processToNumber(inp, i, end, neg, val));
    }

    private long parseLong(final int start, final int len) {
        if (len == 0) return 0L;
        final byte[] inp = input;

        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN) || matchBytes(start, len, BYTES_INF)) return 0L;
            if (matchBytes(start, len, BYTES_NEG_INF)) return 0L;
        }

        int i   = start;
        int end = start + len;
        boolean neg = false;
        if (inp[i] == '-') { neg = true; i++; }

        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);

        long val = 0L;
        return processToNumber(inp, i, end, neg, val);
    }

    private static long processToNumber(byte[] inp, int i, int end, boolean neg, long val) {
        while (i < end) {
            final int d = inp[i++] - '0';
            // this mask keeps all bits except the lowest 4 bits.
            // the if statement is heavily equivalent to this:
            // if (d < 0 || d > 9) throw new NumberFormatException("Not a digit at byte " + (i - 1));
            if ((d & 0xFFFFFFF0) != 0) throw new NumberFormatException("Not a digit at byte " + (i - 1));
            val = val * 10 + d;
        }
        return neg ? -val : val;
    }

    /**
     * Fast double parser with extended POW10 table covering ±22.
     * Eliminates String allocation for the vast majority of real-world doubles
     * (scientific notation up to e±22 is handled natively). Only values with
     * exponents outside ±22, or values requiring IEEE 754 round-trip precision
     * beyond what integer arithmetic provides, fall back to Double.parseDouble
     * with a single String allocation.
     */
    private double parseDouble(final int start, final int len) {
        if (len == 0) return 0.0;
        final byte[] inp = input;

        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN))     return Double.NaN;
            if (matchBytes(start, len, BYTES_INF))     return Double.POSITIVE_INFINITY;
            if (matchBytes(start, len, BYTES_NEG_INF)) return Double.NEGATIVE_INFINITY;
        }

        int i   = start;
        int end = start + len;
        boolean neg = false;
        if (inp[i] == '-') { neg = true; i++; }

        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);

        // Integer part - track overflow; if intPart > 2^53 we may lose precision
        long intPart = 0;
        int  intDigits = 0;
        while (i < end && inp[i] >= '0' && inp[i] <= '9') {
            intPart = intPart * 10 + (inp[i++] - '0');
            intDigits++;
        }

        // If integer part overflows safe integer range, fall back to avoid precision loss
        if (intPart < 0) {
            String s = new String(inp, start, len, StandardCharsets.UTF_8);
            return Double.parseDouble(s);
        }

        double result = intPart;

        if (i < end && inp[i] == '.') {
            i++;
            double frac = 0, div = 1;
            while (i < end && inp[i] >= '0' && inp[i] <= '9') {
                frac = frac * 10 + (inp[i++] - '0');
                div *= 10;
            }
            result += frac / div;
        }

        if (i < end && (inp[i] == 'e' || inp[i] == 'E')) {
            i++;
            boolean expNeg = false;
            if (i < end && inp[i] == '-') { expNeg = true; i++; }
            else if (i < end && inp[i] == '+') i++;

            int expVal = 0;
            while (i < end && inp[i] >= '0' && inp[i] <= '9') {
                expVal = expVal * 10 + (inp[i++] - '0');
            }
            if (expNeg) expVal = -expVal;

            // Extended table covers ±22 - handles essentially all JSON doubles
            if (expVal >= -POW10_OFFSET && expVal <= POW10_OFFSET) {
                result *= POW10[expVal + POW10_OFFSET];
            } else {

                // Outside table range: fall back to String parse for correctness
                String s = new String(inp, start, len, StandardCharsets.UTF_8);
                return Double.parseDouble(s);
            }
        }

        return neg ? -result : result;
    }

    /** Byte-by-byte match against a literal byte array - no String allocation. */
    private boolean matchBytes(int start, int len, byte[] literal) {
        if (len != literal.length) return false;
        final byte[] inp = input;
        for (int i = 0; i < len; i++) if (inp[start + i] != literal[i]) return false;
        return true;
    }

    private RuntimeException error(String message) {
        return wrapExceptions ? new JsonException(message) : new IllegalStateException(message);
    }

    private int findMatchingBrace(int openPos) {
        int    depth      = 1;
        int    word       = (openPos + 1) >>> 6;
        long[] structural = scan.getStructuralMask();
        int    lanes      = structural.length;
        if (lanes == 0) return findMatchingBraceManual(openPos);
        long mask = (word < lanes) ? structural[word] & (~0L << ((openPos + 1) & 63)) : 0L;
        while (word < lanes) {
            while (mask != 0L) {
                final int idx = (word << 6) + Long.numberOfTrailingZeros(mask);
                final byte b = input[idx];
                if (b == '{') depth++;
                else if (b == '}' && --depth == 0) return idx;
                mask &= mask - 1;
            }
            if (++word < lanes) mask = structural[word];
        }
        return findMatchingBraceManual(openPos);
    }

    private int findMatchingBraceManual(int openPos) {
        int     depth   = 1;
        boolean inStr   = false, escaped = false;
        final byte[] inp = input;
        for (int i = openPos + 1; i < limit; i++) {
            final byte b = inp[i];
            if (inStr) {
                if (escaped) { escaped = false; continue; }
                if (b == '\\') { escaped = true; continue; }
                if (b == '"')  { inStr = false;  continue; }
                continue;
            }
            if (b == '"') { inStr = true; continue; }
            if (b == '{') depth++;
            else if (b == '}' && --depth == 0) return i;
        }
        throw error("Unbalanced braces at byte " + openPos);
    }

    // ============================================================
    // UTF-8 encoding helper
    // ============================================================

    private static int writeUtf8(int cp, byte[] buf, int off) {
        if (cp < 0x80) {
            buf[off++] = (byte) cp;
        } else if (cp < 0x800) {
            buf[off++] = (byte) (0xC0 | (cp >>> 6));
            buf[off++] = (byte) (0x80 | (cp & 0x3F));
        } else if (cp < 0x10000) {
            buf[off++] = (byte) (0xE0 | (cp >>> 12));
            buf[off++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
            buf[off++] = (byte) (0x80 | (cp & 0x3F));
        } else {
            buf[off++] = (byte) (0xF0 | (cp >>> 18));
            buf[off++] = (byte) (0x80 | ((cp >>> 12) & 0x3F));
            buf[off++] = (byte) (0x80 | ((cp >>> 6) & 0x3F));
            buf[off++] = (byte) (0x80 | (cp & 0x3F));
        }
        return off;
    }

    private static byte[] growBuffer(byte[] buf, int usedBytes, int needed) {
        byte[] next = new byte[Math.max(needed, buf.length * 2)];
        System.arraycopy(buf, 0, next, 0, usedBytes);
        return next;
    }

    // ============================================================
    // SWAR helpers - operate on 8 bytes packed little-endian in a long
    // ============================================================
    /**
     * Reads 8 bytes from buf[off..off+7] as a little-endian long.
     * Caller must ensure off + 8 <= buf.length.
     */
    private static long readLongLE(byte[] buf, int off) {
        return  ((long) (buf[off] & 0xFF))
            | (((long) (buf[off + 1] & 0xFF)) <<  8)
            | (((long) (buf[off + 2] & 0xFF)) << 16)
            | (((long) (buf[off + 3] & 0xFF)) << 24)
            | (((long) (buf[off + 4] & 0xFF)) << 32)
            | (((long) (buf[off + 5] & 0xFF)) << 40)
            | (((long) (buf[off + 6] & 0xFF)) << 48)
            | (((long) (buf[off + 7] & 0xFF)) << 56);
    }

    /**
     * Returns nonzero (with set high-bits at match positions) if any byte in {@code v}
     * equals the backslash character (0x5C).
     * Algorithm: XOR with broadcast(0x5C) turns matching bytes to 0x00,
     * then standard zero-byte detection finds them.
     */
    @Contract(pure = true)
    private static long swarHasBackslash(long v) {
        final long x = v ^ SWAR_BACKSLASH;
        return (x - SWAR_01) & ~x & SWAR_80;
    }

    /**
     * Returns nonzero (with set high-bits at match positions) if any byte in {@code v}
     * is strictly less than {@code n} (where 1 ≤ n ≤ 128).
     * Used to detect control characters (n=0x20).
     */
    private static long swarHasLessThan(long v, int n) {
        return (v - (SWAR_01 * n)) & ~v & SWAR_80;
    }

    // ============================================================
    // Top-level value parser
    // ============================================================

    /**
     * Public entry point. The root of the input must be a JSON object ({@code {...}}).
     * Throws {@link JsonException} if the root token is anything else.
     */
    public JsonObject parseValue() {
        skipWs();
        if (pos >= limit) throw new JsonException("Unexpected end of input");
        if (input[pos] != '{')
            throw new JsonException("Expected '{' at byte " + pos + " but got '" + (char) input[pos] + "'");
        return parseObjectIterative();
    }

    /**
     * Internal recursive parser - returns any {@link JsonValue}.
     * Used by {@link #parseObjectIterative()} and {@link #parseArrayIterative()}
     * for nested field values and array elements.
     */
    private JsonValue parseValueInternal() {
        skipWs();
        if (pos >= limit) throw new JsonException("Unexpected end of input");

        byte first = input[pos];

        return switch (first) {
            case '"' -> {
                int valueLength = findValueLength(pos);
                yield new JsonString(elementValueAsUnquotedString(pos, valueLength));
            }
            case '{' -> parseObjectIterative();
            case '[' -> parseArrayIterative();
            case 't' -> consumeLiteral("true",  JsonBoolean.of(true));
            case 'f' -> consumeLiteral("false", JsonBoolean.of(false));
            case 'n' -> consumeLiteral("null",  JsonNull.INSTANCE);
            default  -> {
                if ((first >= '0' && first <= '9') || first == '-') {
                    yield parseNumber();
                }
                throw new JsonException("Unknown value type at byte " + pos);
            }
        };
    }

    /**
     * Classifies and parses a JSON number token starting at {@link #pos}.
     *
     * <h3>Dispatch rules (checked in order):</h3>
     * <ol>
     *   <li><b>Explicit suffix</b> on the last character:
     *       <ul>
     *         <li>{@code b} / {@code B} -> {@link JsonByte}</li>
     *         <li>{@code s} / {@code S} -> {@link JsonShort}</li>
     *         <li>{@code L} / {@code l} -> {@link JsonLong}</li>
     *         <li>{@code f} / {@code F} -> {@link JsonFloat}</li>
     *         <li>{@code d} / {@code D} -> {@link JsonDouble}</li>
     *       </ul>
     *       The suffix character is excluded from the numeric payload passed to
     *       the underlying {@code parseInt} / {@code parseLong} / {@code parseDouble}
     *       helpers so they only see clean digits.
     *   </li>
     *   <li><b>Decimal point or exponent</b> ({@code .} / {@code e} / {@code E}) anywhere
     *       in the token -> {@link JsonDouble} (no suffix required).</li>
     *   <li><b>Plain integer</b> (no suffix, no decimal):
     *       <ul>
     *         <li>Fits in {@code int} range -> {@link JsonInteger}</li>
     *         <li>Otherwise -> {@link JsonLong}</li>
     *       </ul>
     *   </li>
     * </ol>
     */
    private JsonNumber parseNumber() {
        final int    tokenStart = pos;
        final int    tokenLen   = findValueLength(tokenStart);
        final byte[] inp        = input;

        final byte lastByte = inp[tokenStart + tokenLen - 1];

        final int payloadLen = tokenLen - 1;

        switch (lastByte) {
            case 'b', 'B' -> {
                pos += tokenLen;
                return new JsonByte((byte) parseInt(tokenStart, payloadLen));
            }
            case 's', 'S' -> {
                pos += tokenLen;
                return new JsonShort((short) parseInt(tokenStart, payloadLen));
            }
            case 'l', 'L' -> {
                pos += tokenLen;
                return new JsonLong(parseLong(tokenStart, payloadLen));
            }
            case 'f', 'F' -> {
                pos += tokenLen;
                return new JsonFloat((float) parseDouble(tokenStart, payloadLen));
            }
            case 'd', 'D' -> {
                pos += tokenLen;
                return new JsonDouble(parseDouble(tokenStart, payloadLen));
            }
        }

        final int tokenEnd = tokenStart + tokenLen;
        for (int i = tokenStart; i < tokenEnd; i++) {
            final byte b = inp[i];
            if (b == '.' || b == 'e' || b == 'E') {
                pos += tokenLen;
                return new JsonDouble(parseDouble(tokenStart, tokenLen));
            }
        }

        pos += tokenLen;
        final long raw = parseLong(tokenStart, tokenLen);
        if (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE) {
            return new JsonInteger((int) raw);
        }
        return new JsonLong(raw);
    }

    private JsonValue consumeLiteral(String literal, JsonValue value) {
        int length = literal.length();
        if (pos + length > limit)
            throw new JsonException("Unexpected end of input when parsing literal at " + pos);

        for (int i = 0; i < length; i++) {
            if (input[pos + i] != literal.charAt(i))
                throw new JsonException("Invalid literal at byte " + pos);
        }
        pos += length;
        return value;
    }

    // Iterative object parser
    @SuppressWarnings("ObjectAllocationInLoop")
    private JsonObject parseObjectIterative() {
        if (!enterObject()) throw new JsonException("Expected '{' at byte " + pos);

        JsonObject obj = new JsonObject();
        while (true) {
            skipWs();
            if (pos < limit && input[pos] == '}') {
                pos++; // consume closing brace
                break;
            }

            if (!nextField()) throw new JsonException("Expected field at byte " + pos);

            String key = fieldNameAsString();
            JsonCursor valueCursor = fieldValueCursor();
            JsonValue jsonValue = valueCursor.parseValueInternal();
            obj.put(key, jsonValue);
        }
        return obj;
    }

    // Iterative array parser
    @SuppressWarnings("ObjectAllocationInLoop")
    private JsonArray parseArrayIterative() {
        if (!enterArray()) throw new JsonException("Expected '[' at byte " + pos);

        JsonArray arr = new JsonArray();
        while (true) {
            skipWs();
            if (pos < limit && input[pos] == ']') {
                pos++; // consume closing bracket
                break;
            }

            if (!nextElement()) throw new JsonException("Expected element at byte " + pos);

            JsonCursor valueCursor = elementValueCursor();
            arr.add(valueCursor.parseValueInternal());
        }
        return arr;
    }
}