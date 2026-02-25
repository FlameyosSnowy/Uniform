package me.flame.uniform.json.parser.lowlevel;

import me.flame.turboscanner.ScanResult;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.exceptions.JsonException;
import me.flame.uniform.json.features.JsonReadFeature;
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
    // Static lookup tables — 256 bytes each, fit in ~4 cache lines
    // ============================================================

    /** true = byte is plain ASCII whitespace (space/tab/CR/LF) */
    private static final boolean[] WS = new boolean[256];

    /**
     * Hex digit value, or -1 for non-hex.
     * Eliminates three branches per hex digit in uXXXX decoding.
     */
    private static final int[] HEX_VAL = new int[256];

    /**
     * Non-NaN/Infinity number start chars.
     * Avoids string allocation to detect these in number parsers.
     */
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

    // Interned byte sequences for NaN / Infinity — avoids String alloc on each parse
    private static final byte[] BYTES_NAN      = {'N', 'a', 'N'};
    private static final byte[] BYTES_INF      = {'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};
    private static final byte[] BYTES_NEG_INF  = {'-', 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};

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

    // ============================================================
    // Feature flags — unpacked booleans are cheaper than EnumSet.contains()
    // ============================================================

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

    // True when ANY non-whitespace extension is active — lets skipWhitespaceAndComments
    // short-circuit the comment branches entirely in the common case.
    private final boolean anyComments;

    // ============================================================
    // Constructors
    // ============================================================

    public JsonCursor(byte[] input, ScanResult scan) {
        this(input, scan, null);
    }

    public JsonCursor(byte[] input, ScanResult scan, JsonConfig config) {
        this.input = input;
        this.scan  = scan;
        this.pos   = 0;
        this.limit = input.length;

        if (config == null) {
            allowJavaComments          = JsonReadFeature.ALLOW_JAVA_COMMENTS.isDefaultValue();
            allowYamlComments          = JsonReadFeature.ALLOW_YAML_COMMENTS.isDefaultValue();
            allowSingleQuotes          = JsonReadFeature.ALLOW_SINGLE_QUOTES.isDefaultValue();
            allowUnquotedFieldNames    = JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.isDefaultValue();
            allowUnescapedControlChars = JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.isDefaultValue();
            allowBackslashEscapingAny  = JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.isDefaultValue();
            allowLeadingZeros          = JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.isDefaultValue();
            allowNonNumericNumbers     = JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.isDefaultValue();
            allowMissingValues         = JsonReadFeature.ALLOW_MISSING_VALUES.isDefaultValue();
            allowTrailingComma         = JsonReadFeature.ALLOW_TRAILING_COMMA.isDefaultValue();
            strictDuplicateDetection   = JsonReadFeature.STRICT_DUPLICATE_DETECTION.isDefaultValue();
            ignoreUndefined            = JsonReadFeature.IGNORE_UNDEFINED.isDefaultValue();
            wrapExceptions             = JsonReadFeature.WRAP_EXCEPTIONS.isDefaultValue();
        } else {
            allowJavaComments          = config.hasReadFeature(JsonReadFeature.ALLOW_JAVA_COMMENTS);
            allowYamlComments          = config.hasReadFeature(JsonReadFeature.ALLOW_YAML_COMMENTS);
            allowSingleQuotes          = config.hasReadFeature(JsonReadFeature.ALLOW_SINGLE_QUOTES);
            allowUnquotedFieldNames    = config.hasReadFeature(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES);
            allowUnescapedControlChars = config.hasReadFeature(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS);
            allowBackslashEscapingAny  = config.hasReadFeature(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER);
            allowLeadingZeros          = config.hasReadFeature(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS);
            allowNonNumericNumbers     = config.hasReadFeature(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS);
            allowMissingValues         = config.hasReadFeature(JsonReadFeature.ALLOW_MISSING_VALUES);
            allowTrailingComma         = config.hasReadFeature(JsonReadFeature.ALLOW_TRAILING_COMMA);
            strictDuplicateDetection   = config.hasReadFeature(JsonReadFeature.STRICT_DUPLICATE_DETECTION);
            ignoreUndefined            = config.hasReadFeature(JsonReadFeature.IGNORE_UNDEFINED);
            wrapExceptions             = config.hasReadFeature(JsonReadFeature.WRAP_EXCEPTIONS);
        }
        anyComments = allowJavaComments || allowYamlComments;
    }

    /** Private sub-cursor constructor — copies flags by value, no config lookup. */
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
    // Public API: Object traversal
    // ============================================================

    public boolean enterObject() {
        skipWs();
        if (pos >= limit || input[pos] != '{') return false;
        pos++;
        skipWs();
        return true;
    }

    public boolean nextField() {
        skipWs();
        if (pos >= limit) return false;

        byte b = input[pos];

        if (b == '}') { pos++; return false; }

        // ALLOW_TRAILING_COMMA
        if (allowTrailingComma && b == ',') {
            pos++;
            skipWs();
            if (pos < limit && input[pos] == '}') { pos++; return false; }
            b = pos < limit ? input[pos] : 0;
        }

        // Field name
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

    // ============================================================
    // Accessors
    // ============================================================

    @Contract(" -> new")
    public @NotNull ByteSlice fieldName() {
        return new ByteSlice(input, fieldNameStart, fieldNameLen);
    }

    @Contract(" -> new")
    public @NotNull ByteSlice fieldValue() {
        return new ByteSlice(input, fieldValueStart, fieldValueLen);
    }

    public int fieldNameHash() {
        // FNV-1a — keep as-is, it's already branchless and allocation-free
        int h   = 0x811c9dc5;
        final byte[] inp = input;
        final int end    = fieldNameStart + fieldNameLen;
        for (int i = fieldNameStart; i < end; i++) {
            h ^= inp[i] & 0xFF;
            h *= 0x01000193;
        }
        return h;
    }

    public boolean fieldNameEquals(@NotNull String expected) {
        final int len = expected.length();
        if (len != fieldNameLen) return false;
        final byte[] inp = input;
        final int    off = fieldNameStart;
        for (int i = 0; i < len; i++) {
            char c = expected.charAt(i);
            // Non-ASCII: fall back to full string compare
            if (c > 0x7F) return expected.equals(new String(inp, off, len, StandardCharsets.UTF_8));
            if (inp[off + i] != (byte) c) return false;
        }
        return true;
    }

    // ── Typed value accessors ────────────────────────────────────────────────

    public int     fieldValueAsInt()    { return parseInt(fieldValueStart, fieldValueLen); }
    public long    fieldValueAsLong()   { return parseLong(fieldValueStart, fieldValueLen); }
    public double  fieldValueAsDouble() { return parseDouble(fieldValueStart, fieldValueLen); }
    public float   fieldValueAsFloat()  { return (float) parseDouble(fieldValueStart, fieldValueLen); }
    public short   fieldValueAsShort()  { return (short) parseInt(fieldValueStart, fieldValueLen); }
    public byte    fieldValueAsByte()   { return (byte)  parseInt(fieldValueStart, fieldValueLen); }

    public boolean fieldValueAsBoolean() {
        // Branchless length-first dispatch — no method call overhead
        final byte[] inp = input;
        final int    s   = fieldValueStart;
        final int    len = fieldValueLen;
        if (len == 4 && inp[s]=='t' && inp[s+1]=='r' && inp[s+2]=='u' && inp[s+3]=='e') return true;
        if (len == 5 && inp[s]=='f' && inp[s+1]=='a' && inp[s+2]=='l' && inp[s+3]=='s' && inp[s+4]=='e') return false;
        // Rare: delegate rather than allocate eagerly
        return "true".equalsIgnoreCase(new String(inp, s, len, StandardCharsets.UTF_8));
    }

    public @NotNull String fieldValueAsUnquotedString() {
        final int s   = fieldValueStart;
        final int len = fieldValueLen;
        if (len >= 2 && input[s] == '"'  && input[s + len - 1] == '"')  return decodeJsonString(s + 1, len - 2);
        if (allowSingleQuotes && len >= 2
            && input[s] == '\'' && input[s + len - 1] == '\'') return decodeJsonString(s + 1, len - 2);
        return new String(input, s, len, StandardCharsets.UTF_8);
    }

    // ============================================================
    // Array traversal
    // ============================================================

    public boolean enterArray() {
        skipWs();
        if (pos >= limit || input[pos] != '[') return false;
        pos++;
        skipWs();
        return true;
    }

    public boolean nextElement() {
        skipWs();
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

    @Contract(" -> new")
    public @NotNull ByteSlice elementValue() {
        return new ByteSlice(input, elementValueStart, elementValueLen);
    }

    public @NotNull JsonCursor elementValueCursor() {
        return new JsonCursor(input, scan, elementValueStart, elementValueStart + elementValueLen, this);
    }

    public @NotNull String elementValueAsUnquotedString() {
        final int s   = elementValueStart;
        final int len = elementValueLen;
        if (len >= 2 && input[s] == '"'  && input[s + len - 1] == '"')  return decodeJsonString(s + 1, len - 2);
        if (allowSingleQuotes && len >= 2
            && input[s] == '\'' && input[s + len - 1] == '\'') return decodeJsonString(s + 1, len - 2);
        return new String(input, s, len, StandardCharsets.UTF_8);
    }

    public @NotNull JsonCursor fieldValueCursor() {
        return new JsonCursor(input, scan, fieldValueStart, fieldValueStart + fieldValueLen, this);
    }

    // ============================================================
    // Whitespace skipping
    // ============================================================

    /**
     * Fast path: pure whitespace skip using the lookup table.
     * Comment handling is pushed into a separate cold method to keep
     * this path as tight as possible — JIT can inline and unroll freely.
     */
    private void skipWs() {
        final byte[] inp = input;
        final int    lim = limit;
        int p = pos;
        while (p < lim && WS[inp[p] & 0xFF]) p++;
        pos = p;
        // Comment handling is rare — only pay for it when enabled
        if (anyComments) skipComments();
    }

    private void skipComments() {
        final byte[] inp = input;
        final int    lim = limit;
        boolean again = true;
        while (again) {
            again = false;
            // Consume trailing whitespace after a comment
            while (pos < lim && WS[inp[pos] & 0xFF]) pos++;

            if (allowJavaComments && pos + 1 < lim && inp[pos] == '/') {
                if (inp[pos + 1] == '/') {
                    pos += 2;
                    while (pos < lim && inp[pos] != '\n') pos++;
                    again = true;
                } else if (inp[pos + 1] == '*') {
                    pos += 2;
                    while (pos + 1 < lim && !(inp[pos] == '*' && inp[pos + 1] == '/')) pos++;
                    if (pos + 1 < lim) pos += 2;
                    again = true;
                }
            }

            if (allowYamlComments && pos < lim && inp[pos] == '#') {
                pos++;
                while (pos < lim && inp[pos] != '\n') pos++;
                again = true;
            }
        }
    }

    // ============================================================
    // String decoding
    // ============================================================

    private @NotNull String decodeJsonString(int start, int len) {
        final byte[] inp = input;
        final int    end = start + len;

        // Fast path: scan for backslash and control chars in one pass
        for (int i = start; i < end; i++) {
            final int b = inp[i] & 0xFF;
            if (b == '\\') return decodeJsonStringSlow(start, end);
            if (!allowUnescapedControlChars && b < 0x20)
                throw error("Unescaped control character at byte " + i);
        }
        // Zero escapes — single allocation
        return new String(inp, start, len, StandardCharsets.UTF_8);
    }

    private @NotNull String decodeJsonStringSlow(int start, int endExclusive) {
        // Pre-size to avoid StringBuilder realloc — actual length <= endExclusive - start
        final StringBuilder sb  = new StringBuilder(endExclusive - start);
        final byte[]        inp = input;
        int segStart = start;

        for (int i = start; i < endExclusive; i++) {
            final int b = inp[i] & 0xFF;

            if (!allowUnescapedControlChars && b < 0x20 && b != '\\')
                throw error("Unescaped control character at byte " + i);

            if (b != '\\') continue;

            // Flush clean segment — bulk copy via String constructor (single native call)
            if (i > segStart) sb.append(new String(inp, segStart, i - segStart, StandardCharsets.UTF_8));

            if (++i >= endExclusive) break;
            final byte esc = inp[i];

            switch (esc) {
                case '"'  -> sb.append('"');
                case '\'' -> {
                    if (allowSingleQuotes || allowBackslashEscapingAny) sb.append('\'');
                    else throw error("Invalid escape \\' at byte " + i);
                }
                case '\\' -> sb.append('\\');
                case '/'  -> sb.append('/');
                case 'b'  -> sb.append('\b');
                case 'f'  -> sb.append('\f');
                case 'n'  -> sb.append('\n');
                case 'r'  -> sb.append('\r');
                case 't'  -> sb.append('\t');
                case 'u'  -> {
                    if (i + 4 >= endExclusive) break;
                    // HEX_VAL table: no branches, no method calls
                    final int h1 = HEX_VAL[inp[i + 1] & 0xFF];
                    final int h2 = HEX_VAL[inp[i + 2] & 0xFF];
                    final int h3 = HEX_VAL[inp[i + 3] & 0xFF];
                    final int h4 = HEX_VAL[inp[i + 4] & 0xFF];
                    if ((h1 | h2 | h3 | h4) < 0) throw error("Invalid \\u escape at byte " + i);
                    sb.append((char)((h1 << 12) | (h2 << 8) | (h3 << 4) | h4));
                    i += 4;
                }
                default -> {
                    if (allowBackslashEscapingAny) sb.append((char) (esc & 0xFF));
                    else throw error("Invalid escape \\" + (char) esc + " at byte " + i);
                }
            }
            segStart = i + 1;
        }

        if (segStart < endExclusive)
            sb.append(new String(inp, segStart, endExclusive - segStart, StandardCharsets.UTF_8));

        return sb.toString();
    }

    // ============================================================
    // String end finding
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
                // q+1 not inside string → this is the real closing quote
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

    // ============================================================
    // Value length
    // ============================================================

    private int findValueLength(int start) {
        return skipValueEnd(start) - start;
    }

    private int skipValueEnd(int start) {
        if (start >= limit) return start;

        final byte first = input[start];

        // Strings
        if (first == '"')                       return findStringEnd(start) + 1;
        if (allowSingleQuotes && first == '\'') return findStringEndManual(start, (byte) '\'') + 1;

        final long[] structural = scan.getStructuralMask();
        final int    lanes      = structural.length;
        if (lanes == 0) return skipValueEndScalar(start);

        int depthObj = 0, depthArr = 0;
        int word = start >>> 6;
        long mask = (word < lanes) ? structural[word] & (~0L << (start & 63)) : 0L;

        while (word < lanes) {
            while (mask != 0L) {
                final int idx = (word << 6) + Long.numberOfTrailingZeros(mask);
                if (idx >= limit) return limit;
                switch (input[idx]) {
                    case '{' -> depthObj++;
                    case '}' -> { if (depthObj == 0 && depthArr == 0) return idx; if (--depthObj < 0) return idx; }
                    case '[' -> depthArr++;
                    case ']' -> { if (depthObj == 0 && depthArr == 0) return idx; if (--depthArr < 0) return idx; }
                    case ',' -> { if (depthObj == 0 && depthArr == 0) return idx; }
                }
                mask &= mask - 1;
            }
            if (++word < lanes) mask = structural[word];
        }
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

        // NaN/Infinity detection without String allocation
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

        int val = 0;
        while (i < end) {
            final int d = inp[i++] - '0';
            if ((d & 0xFFFFFFF0) != 0) throw new NumberFormatException("Not a digit at byte " + (i - 1));
            val = val * 10 + d;
        }
        return neg ? -val : val;
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
        while (i < end) {
            final int d = inp[i++] - '0';
            if ((d & 0xFFFFFFF0) != 0) throw new NumberFormatException("Not a digit at byte " + (i - 1));
            val = val * 10 + d;
        }
        return neg ? -val : val;
    }

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

        long intPart = 0;
        while (i < end && inp[i] >= '0' && inp[i] <= '9') intPart = intPart * 10 + (inp[i++] - '0');

        double result = intPart;

        if (i < end && inp[i] == '.') {
            i++;
            double frac = 0, div = 1;
            while (i < end && inp[i] >= '0' && inp[i] <= '9') { frac = frac * 10 + (inp[i++] - '0'); div *= 10; }
            result += frac / div;
        }

        // Exponent: hand off to JDK (no allocation-free alternative for full accuracy)
        if (i < end && (inp[i] == 'e' || inp[i] == 'E'))
            return Double.parseDouble(new String(inp, start, len, StandardCharsets.UTF_8));

        return neg ? -result : result;
    }

    // ============================================================
    // Helpers
    // ============================================================

    /** Byte-by-byte match against a literal byte array — no String allocation. */
    private boolean matchBytes(int start, int len, byte[] literal) {
        if (len != literal.length) return false;
        final byte[] inp = input;
        for (int i = 0; i < len; i++) if (inp[start + i] != literal[i]) return false;
        return true;
    }

    private RuntimeException error(String message) {
        return wrapExceptions ? new JsonException(message) : new IllegalStateException(message);
    }

    private static int hex(byte b) { return HEX_VAL[b & 0xFF]; }

    // ============================================================
    // Structural helpers (unchanged logic, minor cleanup)
    // ============================================================

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
}