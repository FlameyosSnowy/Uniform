package io.github.flameyossnowy.uniform.json.parser.lowlevel;

import jdk.incubator.vector.ByteVector;
import jdk.incubator.vector.VectorSpecies;
import me.flame.turboscanner.ScanResult;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.dom.JsonArray;
import io.github.flameyossnowy.uniform.json.dom.JsonBoolean;
import io.github.flameyossnowy.uniform.json.dom.JsonByte;
import io.github.flameyossnowy.uniform.json.dom.JsonDouble;
import io.github.flameyossnowy.uniform.json.dom.JsonFloat;
import io.github.flameyossnowy.uniform.json.dom.JsonInteger;
import io.github.flameyossnowy.uniform.json.dom.JsonLong;
import io.github.flameyossnowy.uniform.json.dom.JsonNull;
import io.github.flameyossnowy.uniform.json.dom.JsonNumber;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonShort;
import io.github.flameyossnowy.uniform.json.dom.JsonString;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

@SuppressWarnings({ "unused", "DuplicateExpressions" })
public final class JsonCursor implements JsonReadCursor {
    private static final byte TOK_OTHER     = 0;
    private static final byte TOK_OBJ_OPEN  = 1;
    private static final byte TOK_OBJ_CLOSE = 2;
    private static final byte TOK_ARR_OPEN  = 3;
    private static final byte TOK_ARR_CLOSE = 4;
    private static final byte TOK_COMMA     = 5;
    private static final byte TOK_COLON     = 6;
    private static final byte TOK_QUOTE     = 7;
    private static final byte TOK_WS        = 8;
    private static final byte TOK_DIGIT     = 9;
    private static final byte TOK_MINUS     = 10;
    private static final byte TOK_TRUE      = 11;
    private static final byte TOK_FALSE     = 12;
    private static final byte TOK_NULL_TOK  = 13;
    private static final byte TOK_FLOAT_SIG = 14;  // '.', 'e', 'E'
    private static final byte TOK_SQUOTE    = 15;

    private static final byte[] TOKEN = new byte[256];

    static {
        TOKEN['{'] = TOK_OBJ_OPEN;    TOKEN['}'] = TOK_OBJ_CLOSE;
        TOKEN['['] = TOK_ARR_OPEN;    TOKEN[']'] = TOK_ARR_CLOSE;
        TOKEN[','] = TOK_COMMA;       TOKEN[':'] = TOK_COLON;
        TOKEN['"'] = TOK_QUOTE;       TOKEN['\''] = TOK_SQUOTE;
        TOKEN[' '] = TOKEN['\t'] = TOKEN['\r'] = TOKEN['\n'] = TOK_WS;
        for (int i = '0'; i <= '9'; i++) TOKEN[i] = TOK_DIGIT;
        TOKEN['-'] = TOK_MINUS;
        TOKEN['t'] = TOK_TRUE;
        TOKEN['f'] = TOK_FALSE;
        TOKEN['n'] = TOK_NULL_TOK;
        TOKEN['.'] = TOKEN['e'] = TOKEN['E'] = TOK_FLOAT_SIG;
    }

    /** WS bitset: long[4], only WS[0] is set (space/tab/cr/lf all < 64). */
    private static final long[] WS = new long[4];

    /** Hex digit value, -1 for non-hex. */
    private static final int[] HEX_VAL = new int[256];

    /** True for bytes that can start a number (digit or '-'). */
    private static final boolean[] NUM_START = new boolean[256];

    static {
        WS[0] = (1L << ' ') | (1L << '\t') | (1L << '\r') | (1L << '\n');

        for (int i = 0; i < 256; i++)    HEX_VAL[i] = -1;
        for (int i = '0'; i <= '9'; i++) HEX_VAL[i] = i - '0';
        for (int i = 'a'; i <= 'f'; i++) HEX_VAL[i] = 10 + i - 'a';
        for (int i = 'A'; i <= 'F'; i++) HEX_VAL[i] = 10 + i - 'A';

        for (int i = '0'; i <= '9'; i++) NUM_START[i] = true;
        NUM_START['-'] = true;
    }

    private static final VectorSpecies<Byte> SPECIES = ByteVector.SPECIES_PREFERRED;
    private static final int                 V_LEN   = SPECIES.length();

    private static final long SWAR_01        = 0x0101010101010101L;
    private static final long SWAR_80        = 0x8080808080808080L;
    private static final long SWAR_BACKSLASH = 0x5C5C5C5C5C5C5C5CL;

    private static final byte[] BYTES_NAN     = {'N', 'a', 'N'};
    private static final byte[] BYTES_INF     = {'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};
    private static final byte[] BYTES_NEG_INF = {'-', 'I', 'n', 'f', 'i', 'n', 'i', 't', 'y'};

    /** POW10[i] = 10^(i - POW10_OFFSET). Covers +-22. */
    private static final double[] POW10        = new double[45];
    private static final int      POW10_OFFSET = 22;

    static {
        for (int i = 0; i < POW10.length; i++) {
            POW10[i] = Math.pow(10, i - POW10_OFFSET);
        }
    }

    /** Thread-local decode buffer for escape-sequence slow path. */
    private static final ThreadLocal<byte[]> DECODE_BUFFER =
        ThreadLocal.withInitial(() -> new byte[1024]);

    private final byte[]     input;
    private final ScanResult scan;
    private       int        pos;
    private final int        limit;

    private int fieldNameStart;
    private int fieldNameLen;

    private int fieldValueStart;
    private int fieldValueLen;    // -1 = not yet computed

    private int elementValueStart;
    private int elementValueLen;  // -1 = not yet computed

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
        this.fieldValueLen   = -1;
        this.elementValueLen = -1;

        this.allowJavaComments          = config.hasReadFeature(JsonReadFeature.ALLOW_JAVA_COMMENTS)                    || JsonReadFeature.ALLOW_JAVA_COMMENTS.isDefaultValue();
        this.allowYamlComments          = config.hasReadFeature(JsonReadFeature.ALLOW_YAML_COMMENTS)                    || JsonReadFeature.ALLOW_YAML_COMMENTS.isDefaultValue();
        this.allowSingleQuotes          = config.hasReadFeature(JsonReadFeature.ALLOW_SINGLE_QUOTES)                    || JsonReadFeature.ALLOW_SINGLE_QUOTES.isDefaultValue();
        this.allowUnquotedFieldNames    = config.hasReadFeature(JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES)             || JsonReadFeature.ALLOW_UNQUOTED_FIELD_NAMES.isDefaultValue();
        this.allowUnescapedControlChars = config.hasReadFeature(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS)          || JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS.isDefaultValue();
        this.allowBackslashEscapingAny  = config.hasReadFeature(JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER) || JsonReadFeature.ALLOW_BACKSLASH_ESCAPING_ANY_CHARACTER.isDefaultValue();
        this.allowLeadingZeros          = config.hasReadFeature(JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS)        || JsonReadFeature.ALLOW_LEADING_ZEROS_FOR_NUMBERS.isDefaultValue();
        this.allowNonNumericNumbers     = config.hasReadFeature(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)              || JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS.isDefaultValue();
        this.allowMissingValues         = config.hasReadFeature(JsonReadFeature.ALLOW_MISSING_VALUES)                   || JsonReadFeature.ALLOW_MISSING_VALUES.isDefaultValue();
        this.allowTrailingComma         = config.hasReadFeature(JsonReadFeature.ALLOW_TRAILING_COMMA)                   || JsonReadFeature.ALLOW_TRAILING_COMMA.isDefaultValue();
        this.strictDuplicateDetection   = config.hasReadFeature(JsonReadFeature.STRICT_DUPLICATE_DETECTION)             || JsonReadFeature.STRICT_DUPLICATE_DETECTION.isDefaultValue();
        this.ignoreUndefined            = config.hasReadFeature(JsonReadFeature.IGNORE_UNDEFINED)                       || JsonReadFeature.IGNORE_UNDEFINED.isDefaultValue();
        this.wrapExceptions             = config.hasReadFeature(JsonReadFeature.WRAP_EXCEPTIONS)                        || JsonReadFeature.WRAP_EXCEPTIONS.isDefaultValue();
        this.anyComments                = allowJavaComments || allowYamlComments;
    }

    private JsonCursor(byte[] input, ScanResult scan, int pos, int limit, JsonCursor p) {
        this.input = input;
        this.scan  = scan;
        this.pos   = pos;
        this.limit = limit;
        this.fieldValueLen   = -1;
        this.elementValueLen = -1;

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

    @Override
    public boolean enterObject() {
        skipWs();
        if (pos >= limit || TOKEN[input[pos] & 0xFF] != TOK_OBJ_OPEN) return false;
        pos++;
        skipWs();
        return true;
    }

    @Override
    public boolean enterArray() {
        skipWs();
        if (pos >= limit || TOKEN[input[pos] & 0xFF] != TOK_ARR_OPEN) return false;
        pos++;
        skipWs();
        return true;
    }

    public boolean enterObjectValue() {
        return enterValue(TOK_OBJ_OPEN);
    }

    private boolean enterValue(byte tokObjOpen) {
        pos = fieldValueStart;
        skipWs();
        if (pos >= limit) return false;
        final byte tok = TOKEN[input[pos] & 0xFF];
        if (tok == TOK_NULL_TOK) {
            skipScalar();
            finishFieldAfterValue();
            return false;
        }
        if (tok != tokObjOpen) return false;
        pos++;
        skipWs();
        return true;
    }

    /**
     * Advance into a nested array that is the current field's value, in-place.
     * Same contract as {@link #enterObjectValue()} but for '['.
     */
    public boolean enterArrayValue() {
        return enterValue(TOK_ARR_OPEN);
    }

    /**
     * Advance into a nested object that is the current element's value, in-place.
     * Mirrors {@link #enterObjectValue()} for the array-element context.
     */
    public boolean enterObjectElement() {
        return enterElement(TOK_OBJ_OPEN);
    }

    /**
     * Advance into a nested array that is the current element's value, in-place.
     */
    public boolean enterArrayElement() {
        return enterElement(TOK_ARR_OPEN);
    }

    private boolean enterElement(byte tokArrOpen) {
        pos = elementValueStart;
        skipWs();
        if (pos >= limit) return false;
        final byte tok = TOKEN[input[pos] & 0xFF];
        if (tok == TOK_NULL_TOK) {
            skipScalar();
            finishElementAfterValue();
            return false;
        }
        if (tok != tokArrOpen) return false;
        pos++;
        skipWs();
        return true;
    }

    /**
     * Called after an enterObjectValue/enterArrayValue nested parse completes.
     * Consumes trailing comma so the outer nextField() loop sees the next field.
     */
    public void finishFieldAfterValue() {
        skipWs();
        if (pos < limit && TOKEN[input[pos] & 0xFF] == TOK_COMMA) pos++;
    }

    /** Mirrors finishFieldAfterValue() for the array-element context. */
    public void finishElementAfterValue() {
        skipWs();
        if (pos < limit && TOKEN[input[pos] & 0xFF] == TOK_COMMA) pos++;
    }

    @Override
    public boolean nextField() {
        skipWs();
        if (pos >= limit) return false;

        byte b   = input[pos];
        byte tok = TOKEN[b & 0xFF];

        if (tok == TOK_OBJ_CLOSE) {
            pos++;
            return false;
        }

        if (allowTrailingComma && tok == TOK_COMMA) {
            pos++;
            skipWs();
            if (pos < limit && TOKEN[input[pos] & 0xFF] == TOK_OBJ_CLOSE) {
                pos++;
                return false;
            }
            b   = pos < limit ? input[pos] : 0;
            tok = TOKEN[b & 0xFF];
        }

        // Parse field name
        if (tok == TOK_QUOTE) {
            fieldNameStart = pos + 1;
            int eq         = findStringEnd(pos);
            fieldNameLen   = eq - fieldNameStart;
            pos            = eq + 1;
        } else if (allowSingleQuotes && tok == TOK_SQUOTE) {
            fieldNameStart = pos + 1;
            int eq         = findStringEndManual(pos, (byte) '\'');
            fieldNameLen   = eq - fieldNameStart;
            pos            = eq + 1;
        } else if (allowUnquotedFieldNames) {
            fieldNameStart = pos;
            final byte[] inp = input;
            final int    lim = limit;
            while (pos < lim) {
                final byte ct = TOKEN[inp[pos] & 0xFF];
                if (ct == TOK_COLON || ct == TOK_WS) break;
                pos++;
            }
            fieldNameLen = pos - fieldNameStart;
        } else {
            throw error("Expected field name at byte " + pos);
        }

        skipWs();
        if (pos >= limit || TOKEN[input[pos] & 0xFF] != TOK_COLON)
            throw error("Expected ':' at byte " + pos);
        pos++;
        skipWs();

        // Mark value start; do NOT compute length yet (lazy)
        fieldValueStart = pos;
        fieldValueLen   = -1;  // sentinel: not yet computed

        return true;
    }

    @Override
    public boolean nextElement() {
        skipWs();
        final int lim = this.limit;
        if (pos >= lim) return false;

        byte tok = TOKEN[input[pos] & 0xFF];

        if (tok == TOK_ARR_CLOSE) {
            pos++;
            return false;
        }

        if (allowTrailingComma && tok == TOK_COMMA) {
            pos++;
            skipWs();
            if (pos < lim && TOKEN[input[pos] & 0xFF] == TOK_ARR_CLOSE) {
                pos++;
                return false;
            }
            tok = pos < lim ? TOKEN[input[pos] & 0xFF] : TOK_OTHER;
        }

        if (allowMissingValues && tok == TOK_COMMA) {
            elementValueStart = pos;
            elementValueLen   = 0;
            return true;
        }

        elementValueStart = pos;
        elementValueLen   = -1; // lazy

        return true;
    }

    /**
     * Ensures fieldValueLen is populated. Called by legacy fieldValueAsXxx() methods.
     * Callers must not mix inline-parse and legacy accessors for the same field.
     */
    private void ensureValueLen() {
        if (fieldValueLen < 0) {
            fieldValueLen = findValueLength(fieldValueStart);
        }
    }

    private void ensureElementLen() {
        if (elementValueLen < 0) {
            elementValueLen = findValueLength(elementValueStart);
        }
    }

    /** Parse the current field value as an int in one pass. */
    public int fieldValueParseInt() {
        pos = fieldValueStart;
        final int v = scanParseInt();
        finishFieldAfterValue();
        return v;
    }

    public long fieldValueParseLong() {
        pos = fieldValueStart;
        final long v = scanParseLong();
        finishFieldAfterValue();
        return v;
    }

    public double fieldValueParseDouble() {
        pos = fieldValueStart;
        final double v = scanParseDouble();
        finishFieldAfterValue();
        return v;
    }

    public float fieldValueParseFloat() {
        pos = fieldValueStart;
        final float v = (float) scanParseDouble();
        finishFieldAfterValue();
        return v;
    }

    public boolean fieldValueParseBoolean() {
        pos = fieldValueStart;
        final boolean v = scanParseBoolean();
        finishFieldAfterValue();
        return v;
    }

    public @NotNull String fieldValueParseString() {
        pos = fieldValueStart;
        final String v = scanParseString();
        finishFieldAfterValue();
        return v;
    }

    public int elementValueParseInt() {
        pos = elementValueStart;
        final int v = scanParseInt();
        finishElementAfterValue();
        return v;
    }

    public long elementValueParseLong() {
        pos = elementValueStart;
        final long v = scanParseLong();
        finishElementAfterValue();
        return v;
    }

    public double elementValueParseDouble() {
        pos = elementValueStart;
        final double v = scanParseDouble();
        finishElementAfterValue();
        return v;
    }

    public float elementValueParseFloat() {
        pos = elementValueStart;
        final float v = (float) scanParseDouble();
        finishElementAfterValue();
        return v;
    }

    public boolean elementValueParseBoolean() {
        pos = elementValueStart;
        final boolean v = scanParseBoolean();
        finishElementAfterValue();
        return v;
    }

    public @NotNull String elementValueParseString() {
        pos = elementValueStart;
        final String v = scanParseString();
        finishElementAfterValue();
        return v;
    }

    public void skipFieldValue() {
        pos = fieldValueStart;
        skipValueFull();
        finishFieldAfterValue();
    }

    public void skipElementValue() {
        pos = elementValueStart;
        skipValueFull();
        finishElementAfterValue();
    }

    private int scanParseInt() {
        return Math.toIntExact(scanParseLong());
    }

    private long scanParseLong() {
        final byte[] inp = input;
        int p = pos;
        if (p >= limit) return 0L;

        boolean neg = false;
        if (inp[p] == '-') {
            neg = true;
            p++;
        }

        long val = 0L;
        while (p < limit) {
            final int d = inp[p] - '0';
            if ((d & 0xFFFFFFF0) != 0) break; // non-digit stops parsing
            val = val * 10 + d;
            p++;
        }
        pos = p;
        return neg ? -val : val;
    }

    private double scanParseDouble() {
        final byte[] inp = input;
        int p = pos;
        if (p >= limit) return 0.0;

        boolean neg = false;
        if (inp[p] == '-') {
            neg = true;
            p++;
        }

        long intPart = 0;
        while (p < limit) {
            final byte b = inp[p];
            if (TOKEN[b & 0xFF] == TOK_FLOAT_SIG) break;
            final int d = b - '0';
            if ((d & 0xFFFFFFF0) != 0) break;
            intPart = intPart * 10 + d;
            if (intPart < 0) {
                final int tokenStart = fieldValueStart;
                while (p < limit && !isValueTerminator(TOKEN[inp[p] & 0xFF])) p++;
                pos = p;
                return Double.parseDouble(new String(inp, tokenStart, p - tokenStart, StandardCharsets.UTF_8));
            }
            p++;
        }

        double result = intPart;

        if (p < limit && inp[p] == '.') {
            p++;
            long fracPart = 0;
            int  fracLen  = 0;
            while (p < limit) {
                final int d = inp[p] - '0';
                if ((d & 0xFFFFFFF0) != 0) break;
                if (fracLen < 18) {
                    fracPart = fracPart * 10 + d;
                    fracLen++;
                }
                p++;
            }
            if (fracLen > 0) {
                result += (fracLen <= POW10_OFFSET)
                    ? fracPart * POW10[POW10_OFFSET - fracLen]
                    : (double) fracPart / Math.pow(10, fracLen);
            }
        }

        if (p < limit && (inp[p] == 'e' || inp[p] == 'E')) {
            p++;
            boolean expNeg = false;
            if (p < limit && inp[p] == '-') {
                expNeg = true;
                p++;
            } else if (p < limit && inp[p] == '+') {
                p++;
            }
            int expVal = 0;
            while (p < limit && inp[p] >= '0' && inp[p] <= '9') {
                expVal = expVal * 10 + (inp[p++] - '0');
            }
            if (expNeg) expVal = -expVal;
            if (expVal >= -POW10_OFFSET && expVal <= POW10_OFFSET) {
                result *= POW10[expVal + POW10_OFFSET];
            } else {
                final int start = fieldValueStart;
                while (p < limit && !isValueTerminator(TOKEN[inp[p] & 0xFF])) p++;
                pos = p;
                return Double.parseDouble(new String(inp, start, p - start, StandardCharsets.UTF_8));
            }
        }

        pos = p;
        return neg ? -result : result;
    }

    private boolean scanParseBoolean() {
        final byte[] inp = input;
        final int    p   = pos;
        if (p + 3 < limit) {
            // Pack 4 bytes LE
            final int w = (inp[p]     & 0xFF)
                | ((inp[p + 1] & 0xFF) << 8)
                | ((inp[p + 2] & 0xFF) << 16)
                | ((inp[p + 3] & 0xFF) << 24);
            if (w == 0x65757274) {
                pos = p + 4;
                return true;   // "true"
            }
            if (p + 4 < limit && w == 0x736C6166 && inp[p + 4] == 'e') {
                pos = p + 5;
                return false;  // "false"
            }
        }
        skipScalar();
        return false;
    }

    private @NotNull String scanParseString() {
        final byte[] inp = input;
        if (pos >= limit) return "";
        final byte first = inp[pos];
        if (TOKEN[first & 0xFF] == TOK_QUOTE) {
            final int contentStart = pos + 1;
            final int closeQuote   = findStringEnd(pos);
            final String s = decodeJsonString(contentStart, closeQuote - contentStart);
            pos = closeQuote + 1;
            return s;
        }
        if (allowSingleQuotes && TOKEN[first & 0xFF] == TOK_SQUOTE) {
            final int contentStart = pos + 1;
            final int closeQuote   = findStringEndManual(pos, (byte) '\'');
            final String s = decodeJsonString(contentStart, closeQuote - contentStart);
            pos = closeQuote + 1;
            return s;
        }
        final int start = pos;
        skipScalar();
        return new String(inp, start, pos - start, StandardCharsets.UTF_8);
    }

    /** Returns true if the token is a value terminator at depth 0. */
    private static boolean isValueTerminator(byte tok) {
        return tok == TOK_COMMA
            || tok == TOK_OBJ_CLOSE
            || tok == TOK_ARR_CLOSE
            || tok == TOK_WS;
    }

    /** Skip a scalar value (non-string, non-nested) from current pos. */
    private void skipScalar() {
        final byte[] inp = input;
        final int    lim = limit;
        while (pos < lim && !isValueTerminator(TOKEN[inp[pos] & 0xFF])) pos++;
    }

    /**
     * Skip any value (scalar, string, or nested) from current pos.
     * Strings: SIMD scan to closing quote.
     * Nested:  structural bitmask walk, O(n/64).
     * Scalars: byte scan to delimiter.
     */
    private void skipValueFull() {
        if (pos >= limit) return;
        final byte tok = TOKEN[input[pos] & 0xFF];

        if (tok == TOK_QUOTE) {
            pos = findStringEnd(pos) + 1;
            return;
        }
        if (allowSingleQuotes && tok == TOK_SQUOTE) {
            pos = findStringEndManual(pos, (byte) '\'') + 1;
            return;
        }
        if (tok == TOK_OBJ_OPEN || tok == TOK_ARR_OPEN) {
            pos = skipValueEnd(pos);
            return;
        }
        skipScalar();
    }

    @Override
    @Contract(" -> new")
    public @NotNull ByteSlice fieldName() {
        return new ByteSlice(input, fieldNameStart, fieldNameLen);
    }

    @Override
    public @NotNull String fieldNameAsString() {
        final byte[] inp = input;
        final int    off = fieldNameStart;
        final int    len = fieldNameLen;
        // SWAR scan for any byte >= 0x80
        int i = 0;
        final int limit8 = len - 7;
        while (i < limit8) {
            if ((readLongLE(inp, off + i) & SWAR_80) != 0)
                return new String(inp, off, len, StandardCharsets.UTF_8);
            i += 8;
        }
        while (i < len) {
            if ((inp[off + i] & 0x80) != 0)
                return new String(inp, off, len, StandardCharsets.UTF_8);
            i++;
        }
        return new String(inp, off, len); // latin-1: no charset decode
    }

    @Override
    public int fieldNameHash() {
        int h = 0x811c9dc5;
        final byte[] inp = input;
        final int    end = fieldNameStart + fieldNameLen;
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

        // <=8 chars: SWAR long-pack
        if (len <= 8) {
            long inputWord = 0L, expectWord = 0L;
            for (int i = 0; i < len; i++) {
                final char c = expected.charAt(i);
                if (c > 0x7F)
                    return expected.equals(new String(inp, off, len, StandardCharsets.UTF_8));
                inputWord  |= ((long) (inp[off + i] & 0xFF)) << (i << 3);
                expectWord |= ((long) c)                      << (i << 3);
            }
            return inputWord == expectWord;
        }

        // >8 chars: SIMD bulk + SWAR tail
        byte[] expBytes = expected.getBytes(StandardCharsets.UTF_8);
        if (expBytes.length != len) return false;
        int i = 0;
        final int bulkLimit = len - V_LEN;
        while (i <= bulkLimit) {
            if (!ByteVector.fromArray(SPECIES, inp, off + i)
                .eq(ByteVector.fromArray(SPECIES, expBytes, i))
                .allTrue()) return false;
            i += V_LEN;
        }
        final int tail8 = len - 8;
        while (i <= tail8) {
            if (readLongLE(inp, off + i) != readLongLE(expBytes, i)) return false;
            i += 8;
        }
        while (i < len) {
            if (inp[off + i] != expBytes[i]) return false;
            i++;
        }
        return true;
    }

    @Contract(" -> new")
    @Override
    public @NotNull ByteSlice fieldValue() {
        ensureValueLen();
        return new ByteSlice(input, fieldValueStart, fieldValueLen);
    }

    @Override
    public int fieldValueAsInt() {
        ensureValueLen();
        final int v = parseInt(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public long fieldValueAsLong() {
        ensureValueLen();
        final long v = parseLong(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public double fieldValueAsDouble() {
        ensureValueLen();
        final double v = parseDouble(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public float fieldValueAsFloat() {
        ensureValueLen();
        final float v = (float) parseDouble(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public short fieldValueAsShort() {
        ensureValueLen();
        final short v = (short) parseInt(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public byte fieldValueAsByte() {
        ensureValueLen();
        final byte v = (byte) parseInt(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public boolean fieldValueAsBoolean() {
        ensureValueLen();
        final boolean v = parseBoolean(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public @NotNull String fieldValueAsUnquotedString() {
        ensureValueLen();
        final String v = unquoteString(fieldValueStart, fieldValueLen);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return v;
    }

    @Override
    public @NotNull JsonCursor fieldValueCursor() {
        ensureValueLen();
        final JsonCursor sub = new JsonCursor(input, scan, fieldValueStart, fieldValueStart + fieldValueLen, this);
        pos = fieldValueStart + fieldValueLen;
        finishFieldAfterValue();
        return sub;
    }

    @Override
    @Contract(" -> new")
    public @NotNull ByteSlice elementValue() {
        ensureElementLen();
        return new ByteSlice(input, elementValueStart, elementValueLen);
    }

    @Override
    public int elementValueAsInt() {
        ensureElementLen();
        final int v = parseInt(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public long elementValueAsLong() {
        ensureElementLen();
        final long v = parseLong(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public double elementValueAsDouble() {
        ensureElementLen();
        final double v = parseDouble(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public float elementValueAsFloat() {
        ensureElementLen();
        final float v = (float) parseDouble(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public short elementValueAsShort() {
        ensureElementLen();
        final short v = (short) parseInt(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public byte elementValueAsByte() {
        ensureElementLen();
        final byte v = (byte) parseInt(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public boolean elementValueAsBoolean() {
        ensureElementLen();
        final boolean v = parseBoolean(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    @Override
    public @NotNull String elementValueAsUnquotedString() {
        ensureElementLen();
        final String v = unquoteString(elementValueStart, elementValueLen);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return v;
    }

    public @NotNull String elementValueAsUnquotedString(int s, int len) {
        return unquoteString(s, len);
    }

    @Override
    public @NotNull JsonCursor elementValueCursor() {
        ensureElementLen();
        final JsonCursor sub = new JsonCursor(input, scan, elementValueStart, elementValueStart + elementValueLen, this);
        pos = elementValueStart + elementValueLen;
        finishElementAfterValue();
        return sub;
    }

    private boolean parseBoolean(int s, int len) {
        final byte[] inp = input;
        if (len == 4) {
            final int word = (inp[s]     & 0xFF)
                | ((inp[s + 1] & 0xFF) << 8)
                | ((inp[s + 2] & 0xFF) << 16)
                | ((inp[s + 3] & 0xFF) << 24);
            if (word == 0x65757274) return true;
            final byte b0 = inp[s], b1 = inp[s + 1], b2 = inp[s + 2], b3 = inp[s + 3];
            if ((b0 == 't' || b0 == 'T') && (b1 == 'r' || b1 == 'R')
                && (b2 == 'u' || b2 == 'U') && (b3 == 'e' || b3 == 'E')) return true;
        }
        if (len == 5) {
            final int word = (inp[s]     & 0xFF)
                | ((inp[s + 1] & 0xFF) << 8)
                | ((inp[s + 2] & 0xFF) << 16)
                | ((inp[s + 3] & 0xFF) << 24);
            if (word == 0x736C6166 && inp[s + 4] == 'e') return false;
            final byte b0 = inp[s], b1 = inp[s + 1], b2 = inp[s + 2], b3 = inp[s + 3], b4 = inp[s + 4];
            if ((b0 == 'f' || b0 == 'F') && (b1 == 'a' || b1 == 'A')
                && (b2 == 'l' || b2 == 'L') && (b3 == 's' || b3 == 'S')
                && (b4 == 'e' || b4 == 'E')) return false;
        }
        return "true".equalsIgnoreCase(new String(inp, s, len, StandardCharsets.UTF_8));
    }

    private @NotNull String unquoteString(int s, int len) {
        if (len >= 2) {
            final byte first = input[s], last = input[s + len - 1];
            if (first == '"' && last == '"')
                return decodeJsonString(s + 1, len - 2);
            if (allowSingleQuotes && first == '\'' && last == '\'')
                return decodeJsonString(s + 1, len - 2);
        }
        return new String(input, s, len, StandardCharsets.UTF_8);
    }

    private void skipWs() {
        final byte[] inp = input;
        final int    lim = limit;
        int p = pos;
        // All WS chars < 0x21; single lt() replaces 4 eq() + 3 or()
        final int bulkLimit = lim - V_LEN;
        while (p <= bulkLimit) {
            ByteVector v    = ByteVector.fromArray(SPECIES, inp, p);
            var        isWs = v.lt((byte) 0x21);
            if (isWs.allTrue()) {
                p += V_LEN;
                continue;
            }
            p += isWs.not().firstTrue();
            pos = p;
            if (anyComments) checkComment(inp, p, lim);
            return;
        }
        while (p < lim) {
            final int b = inp[p] & 0xFF;
            if ((WS[b >>> 6] & (1L << (b & 63))) == 0) break;
            p++;
        }
        pos = p;
        if (anyComments && p < lim) checkComment(inp, p, lim);
    }

    private void checkComment(byte[] inp, int p, int lim) {
        final int b = inp[p] & 0xFF;
        if ((allowJavaComments && b == '/') || (allowYamlComments && b == '#')) skipComments();
    }

    private void skipComments() {
        final byte[] inp = input;
        final int    lim = limit;
        int p = this.pos;
        boolean again = true;
        while (again) {
            again = false;
            while (p < lim) {
                final byte b = inp[p];
                if ((WS[b >>> 6] & (1L << (b & 63))) == 0) break;
                p++;
            }
            if (p >= lim) break;
            byte b = inp[p];
            if (allowJavaComments && p + 1 < lim && b == '/') {
                if (inp[p + 1] == '/') {
                    p += 2;
                    while (p < lim && inp[p] != '\n') p++;
                    again = true;
                } else if (inp[p + 1] == '*') {
                    p += 2;
                    while (p + 1 < lim && !(inp[p] == '*' && inp[p + 1] == '/')) p++;
                    if (p + 1 < lim) p += 2;
                    again = true;
                }
            }
            if (allowYamlComments && p < lim && inp[p] == '#') {
                do { p++; } while (p < lim && inp[p] != '\n');
                again = true;
            }
        }
        this.pos = p;
    }

    private @NotNull String decodeJsonString(int start, int len) {
        final byte[]  inp       = input;
        final int     end       = start + len;
        final boolean checkCtrl = !allowUnescapedControlChars;
        int i = start;
        boolean pureAscii = true;

        final int bulkLimit = end - V_LEN;
        while (i <= bulkLimit) {
            ByteVector v = ByteVector.fromArray(SPECIES, inp, i);
            if (v.eq((byte) '\\').anyTrue())
                return decodeJsonStringSlow(start, end);
            if (v.lt((byte) 0).anyTrue())
                pureAscii = false;
            if (checkCtrl && v.lt((byte) 0x20).anyTrue())
                throw error("Unescaped control character near byte " + i);
            i += V_LEN;
        }
        final int limit8 = end - 7;
        while (i < limit8) {
            final long word = readLongLE(inp, i);
            if (swarHasBackslash(word) != 0)
                return decodeJsonStringSlow(start, end);
            if ((word & SWAR_80) != 0)
                pureAscii = false;
            if (checkCtrl && swarHasLessThan(word, 0x20) != 0)
                throw error("Unescaped control character near byte " + i);
            i += 8;
        }
        while (i < end) {
            final int b = inp[i] & 0xFF;
            if (b == '\\')
                return decodeJsonStringSlow(start, end);
            if (b >= 0x80)
                pureAscii = false;
            if (checkCtrl && b < 0x20)
                throw error("Unescaped control character at byte " + i);
            i++;
        }
        return pureAscii
            ? new String(inp, start, len)
            : new String(inp, start, len, StandardCharsets.UTF_8);
    }

    private static int findNextBackslashOrControl(byte[] inp, int from, int end, boolean checkCtrl) {
        int j = from;
        final int bulkLimit = end - V_LEN;
        while (j <= bulkLimit) {
            ByteVector v        = ByteVector.fromArray(SPECIES, inp, j);
            var        hasSlash = v.eq((byte) '\\');
            var        hasCtrl  = checkCtrl ? v.lt((byte) 0x20) : hasSlash.and(hasSlash.not());
            var        hits     = hasSlash.or(hasCtrl);
            if (hits.anyTrue()) return j + hits.firstTrue();
            j += V_LEN;
        }
        final int limit8 = end - 7;
        while (j < limit8) {
            final long word = readLongLE(inp, j);
            final long hits = swarHasBackslash(word);
            if (hits != 0) return j + (Long.numberOfTrailingZeros(hits) >>> 3);
            if (checkCtrl && swarHasLessThan(word, 0x20) != 0) {
                for (int k = j; k < j + 8; k++) {
                    if ((inp[k] & 0xFF) < 0x20) return k;
                }
            }
            j += 8;
        }
        while (j < end) {
            final int c = inp[j] & 0xFF;
            if (c == '\\') return j;
            if (checkCtrl && c < 0x20) return j;
            j++;
        }
        return end;
    }

    private @NotNull String decodeJsonStringSlow(int start, int endExclusive) {
        final byte[]  inp       = input;
        final boolean checkCtrl = !allowUnescapedControlChars;
        byte[] buf = DECODE_BUFFER.get();
        final int len = endExclusive - start;
        if (buf.length < len) {
            buf = new byte[Math.max(len, buf.length * 2)];
            DECODE_BUFFER.set(buf);
        }
        int out = 0, i = start;
        boolean pureAscii = true;

        while (i < endExclusive) {
            final int b = inp[i] & 0xFF;
            if (checkCtrl && b < 0x20)
                throw error("Unescaped control character at byte " + i);
            if (b != '\\') {
                int j = findNextBackslashOrControl(inp, i + 1, endExclusive, checkCtrl);
                final int copyLen = j - i;
                if (out + copyLen > buf.length) {
                    buf = growBuffer(buf, out, out + copyLen);
                    DECODE_BUFFER.set(buf);
                }
                if (pureAscii) {
                    int k = i;
                    final int k8 = i + copyLen - 7;
                    while (k < k8) {
                        if ((readLongLE(inp, k) & SWAR_80) != 0) {
                            pureAscii = false;
                            break;
                        }
                        k += 8;
                    }
                    if (pureAscii) {
                        while (k < j) {
                            if ((inp[k++] & 0x80) != 0) {
                                pureAscii = false;
                                break;
                            }
                        }
                    }
                }
                System.arraycopy(inp, i, buf, out, copyLen);
                out += copyLen;
                i = j;
                continue;
            }
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
                    if ((h1 | h2 | h3 | h4) < 0)
                        throw error("Invalid \\u escape at byte " + (i - 2));
                    i += 4;
                    int cp = (h1 << 12) | (h2 << 8) | (h3 << 4) | h4;
                    // surrogate pair
                    if (cp >= 0xD800 && cp <= 0xDBFF
                        && i + 5 < endExclusive
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
                    if (cp >= 0x80) pureAscii = false;
                    out = writeUtf8(cp, buf, out);
                }
                default -> {
                    if (allowBackslashEscapingAny) buf[out++] = esc;
                    else throw error("Invalid escape \\" + (char) esc + " at byte " + (i - 1));
                }
            }
        }
        return pureAscii
            ? new String(buf, 0, out)
            : new String(buf, 0, out, StandardCharsets.UTF_8);
    }

    private int findStringEnd(int startQuote) {
        final int    from   = startQuote + 1;
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
        int i = startQuote + 1;
        final int bulkLimit = lim - V_LEN;
        while (i <= bulkLimit) {
            ByteVector v    = ByteVector.fromArray(SPECIES, inp, i);
            var        hits = v.eq(closing).or(v.eq((byte) '\\'));
            if (hits.anyTrue()) {
                i += hits.firstTrue();
                break;
            }
            i += V_LEN;
        }
        boolean escaped = false;
        while (i < lim) {
            final byte b = inp[i];
            if (escaped)      { escaped = false; i++; continue; }
            if (b == '\\')    { escaped = true;  i++; continue; }
            if (b == closing) return i;
            i++;
        }
        throw error("Unterminated string at byte " + startQuote);
    }

    private int findValueLength(int start) {
        return skipValueEnd(start) - start;
    }

    private int skipValueEnd(int start) {
        if (start >= limit) return start;
        final byte ft = TOKEN[input[start] & 0xFF];
        if (ft == TOK_QUOTE)
            return findStringEnd(start) + 1;
        if (allowSingleQuotes && ft == TOK_SQUOTE)
            return findStringEndManual(start, (byte) '\'') + 1;

        final long[] structural = scan.getStructuralMask();
        final int    lanes      = structural.length;
        if (lanes == 0) return skipValueEndScalar(start);

        int  depthObj = 0, depthArr = 0;
        int  word = start >>> 6;
        long mask = (word < lanes) ? structural[word] & (~0L << (start & 63)) : 0L;
        while (word < lanes) {
            while (mask != 0L) {
                final int idx = (word << 6) + Long.numberOfTrailingZeros(mask);
                if (idx >= limit) return limit;
                switch (TOKEN[input[idx] & 0xFF]) {
                    case TOK_OBJ_OPEN  -> depthObj++;
                    case TOK_OBJ_CLOSE -> {
                        if (depthObj == 0 && depthArr == 0) return idx;
                        if (--depthObj < 0) return idx;
                    }
                    case TOK_ARR_OPEN  -> depthArr++;
                    case TOK_ARR_CLOSE -> {
                        if (depthObj == 0 && depthArr == 0) return idx;
                        if (--depthArr < 0) return idx;
                    }
                    case TOK_COMMA -> {
                        if (depthObj == 0 && depthArr == 0) return idx;
                    }
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
            final byte c   = inp[i];
            final byte tok = TOKEN[c & 0xFF];
            if (inStr) {
                if (escaped)       { escaped = false; continue; }
                if (c == '\\')     { escaped = true;  continue; }
                if (c == strClose) { inStr   = false; continue; }
                continue;
            }
            if (tok == TOK_QUOTE) {
                inStr = true; strClose = '"'; continue;
            }
            if (allowSingleQuotes && tok == TOK_SQUOTE) {
                inStr = true; strClose = '\''; continue;
            }
            switch (tok) {
                case TOK_OBJ_OPEN  -> depthObj++;
                case TOK_OBJ_CLOSE -> { if (depthObj == 0 && depthArr == 0) return i; depthObj--; }
                case TOK_ARR_OPEN  -> depthArr++;
                case TOK_ARR_CLOSE -> { if (depthObj == 0 && depthArr == 0) return i; depthArr--; }
                case TOK_COMMA, TOK_WS -> { if (depthObj == 0 && depthArr == 0) return i; }
            }
        }
        return lim;
    }

    private int parseInt(final int start, final int len) {
        if (len == 0) return 0;
        final byte[] inp = input;
        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN)
                || matchBytes(start, len, BYTES_INF)
                || matchBytes(start, len, BYTES_NEG_INF)) return 0;
        }
        int i = start, end = start + len;
        boolean neg = false;
        if (inp[i] == '-') {
            neg = true;
            i++;
        }
        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);
        return Math.toIntExact(processToNumber(inp, i, end, neg, 0L));
    }

    private long parseLong(final int start, final int len) {
        if (len == 0) return 0L;
        final byte[] inp = input;
        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN)
                || matchBytes(start, len, BYTES_INF)
                || matchBytes(start, len, BYTES_NEG_INF)) return 0L;
        }
        int i = start, end = start + len;
        boolean neg = false;
        if (inp[i] == '-') {
            neg = true;
            i++;
        }
        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);
        return processToNumber(inp, i, end, neg, 0L);
    }

    private static long processToNumber(byte[] inp, int i, int end, boolean neg, long val) {
        final int digits = end - i;

        if (digits == 1) {
            final int d = inp[i] - '0';
            if ((d & 0xFFFFFFF0) != 0)
                throw new NumberFormatException("Not a digit at byte " + i);
            return neg ? -d : d;
        }

        if (digits == 2) {
            final int w  = (inp[i] & 0xFF) | ((inp[i + 1] & 0xFF) << 8);
            final int lo = (w & 0xFF) - '0';
            final int hi = (w >> 8)   - '0';
            if ((lo | hi) > 9)
                throw new NumberFormatException("Not a digit near byte " + i);
            final long r = lo * 10L + hi;
            return neg ? -r : r;
        }

        if (digits == 3) {
            final int d0 = inp[i] - '0';
            final int w  = (inp[i + 1] & 0xFF) | ((inp[i + 2] & 0xFF) << 8);
            final int d1 = (w & 0xFF) - '0';
            final int d2 = (w >> 8)   - '0';
            if (((d0 | d1 | d2) & 0xFFFFFFF0) != 0)
                throw new NumberFormatException("Not a digit near byte " + i);
            final long r = d0 * 100L + d1 * 10L + d2;
            return neg ? -r : r;
        }

        if (digits == 4) {
            final long r = processInputAtIndex(inp, i);
            return neg ? -r : r;
        }

        while (i < end) {
            final int d = inp[i++] - '0';
            if ((d & 0xFFFFFFF0) != 0)
                throw new NumberFormatException("Not a digit at byte " + (i - 1));
            val = val * 10 + d;
        }
        return neg ? -val : val;
    }

    private static long processInputAtIndex(byte[] inp, int i) {
        final int w = (inp[i]     & 0xFF)
            | ((inp[i + 1] & 0xFF) << 8)
            | ((inp[i + 2] & 0xFF) << 16)
            | ((inp[i + 3] & 0xFF) << 24);
        final int d = w - 0x30303030;
        if ((d & 0xF0F0F0F0) != 0)
            throw new NumberFormatException("Not a digit near byte " + i);
        final int  d0 = d         & 0xFF;
        final int  d1 = (d >> 8)  & 0xFF;
        final int  d2 = (d >> 16) & 0xFF;
        final int  d3 = (d >> 24) & 0xFF;
        final long r  = d0 * 1000L + d1 * 100L + d2 * 10L + d3;
        return r;
    }

    private double parseDouble(final int start, final int len) {
        if (len == 0) return 0.0;
        final byte[] inp = input;
        if (allowNonNumericNumbers && !NUM_START[inp[start] & 0xFF]) {
            if (matchBytes(start, len, BYTES_NAN))     return Double.NaN;
            if (matchBytes(start, len, BYTES_INF))     return Double.POSITIVE_INFINITY;
            if (matchBytes(start, len, BYTES_NEG_INF)) return Double.NEGATIVE_INFINITY;
        }
        int i = start, end = start + len;
        boolean neg = false;
        if (inp[i] == '-') {
            neg = true;
            i++;
        }
        if (!allowLeadingZeros && i + 1 < end && inp[i] == '0' && inp[i + 1] >= '0')
            throw error("Leading zeros not allowed at byte " + i);

        long intPart = 0;
        while (i < end) {
            final byte b = inp[i];
            if (TOKEN[b & 0xFF] == TOK_FLOAT_SIG) break;
            final int d = b - '0';
            if ((d & 0xFFFFFFF0) != 0) break;
            intPart = intPart * 10 + d;
            if (intPart < 0)
                return Double.parseDouble(new String(inp, start, len, StandardCharsets.UTF_8));
            i++;
        }

        double result = intPart;

        if (i < end && inp[i] == '.') {
            i++;
            long fracPart = 0;
            int  fracLen  = 0;
            while (i < end) {
                final int d = inp[i] - '0';
                if ((d & 0xFFFFFFF0) != 0) break;
                if (fracLen < 18) {
                    fracPart = fracPart * 10 + d;
                    fracLen++;
                }
                i++;
            }
            if (fracLen > 0) {
                result += (fracLen <= POW10_OFFSET)
                    ? fracPart * POW10[POW10_OFFSET - fracLen]
                    : (double) fracPart / Math.pow(10, fracLen);
            }
        }

        if (i < end && (inp[i] == 'e' || inp[i] == 'E')) {
            i++;
            boolean expNeg = false;
            if (i < end && inp[i] == '-') {
                expNeg = true;
                i++;
            } else if (i < end && inp[i] == '+') {
                i++;
            }
            int expVal = 0;
            while (i < end && inp[i] >= '0' && inp[i] <= '9') {
                expVal = expVal * 10 + (inp[i++] - '0');
            }
            if (expNeg) expVal = -expVal;
            if (expVal >= -POW10_OFFSET && expVal <= POW10_OFFSET) {
                result *= POW10[expVal + POW10_OFFSET];
            } else {
                return Double.parseDouble(new String(inp, start, len, StandardCharsets.UTF_8));
            }
        }

        return neg ? -result : result;
    }

    private boolean matchBytes(int start, int len, byte[] literal) {
        if (len != literal.length) return false;
        final byte[] inp = input;
        for (int i = 0; i < len; i++) {
            if (inp[start + i] != literal[i]) return false;
        }
        return true;
    }

    private RuntimeException error(String message) {
        return wrapExceptions
            ? new JsonException(message)
            : new IllegalStateException(message);
    }

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

    private static byte[] growBuffer(byte[] buf, int used, int needed) {
        byte[] next = new byte[Math.max(needed, buf.length * 2)];
        System.arraycopy(buf, 0, next, 0, used);
        return next;
    }

    private static long readLongLE(byte[] buf, int off) {
        return  ((long) (buf[off]     & 0xFF))
            | (((long) (buf[off + 1] & 0xFF)) << 8)
            | (((long) (buf[off + 2] & 0xFF)) << 16)
            | (((long) (buf[off + 3] & 0xFF)) << 24)
            | (((long) (buf[off + 4] & 0xFF)) << 32)
            | (((long) (buf[off + 5] & 0xFF)) << 40)
            | (((long) (buf[off + 6] & 0xFF)) << 48)
            | (((long) (buf[off + 7] & 0xFF)) << 56);
    }

    @Contract(pure = true)
    private static long swarHasBackslash(long v) {
        final long x = v ^ SWAR_BACKSLASH;
        return (x - SWAR_01) & ~x & SWAR_80;
    }

    private static long swarHasLessThan(long v, int n) {
        return (v - (SWAR_01 * n)) & ~v & SWAR_80;
    }

    public JsonValue parseValue() {
        return parseValueInternal();
    }

    private JsonValue parseValueInternal() {
        skipWs();
        if (pos >= limit) throw new JsonException("Unexpected end of input");
        final byte tok = TOKEN[input[pos] & 0xFF];
        return switch (tok) {
            case TOK_QUOTE -> {
                final int cs = pos + 1;
                final int cq = findStringEnd(pos);
                final String s = decodeJsonString(cs, cq - cs);
                pos = cq + 1;
                yield new JsonString(s);
            }
            case TOK_OBJ_OPEN         -> parseObjectIterative();
            case TOK_ARR_OPEN         -> parseArrayIterative();
            case TOK_TRUE             -> consumeLiteralTrue();
            case TOK_FALSE            -> consumeLiteralFalse();
            case TOK_NULL_TOK         -> consumeLiteralNull();
            case TOK_DIGIT, TOK_MINUS -> parseNumber();
            default -> throw new JsonException(
                "Unknown value at byte " + pos + " ('" + (char) (input[pos] & 0xFF) + "')");
        };
    }

    private JsonValue consumeLiteralTrue() {
        if (pos + 4 > limit)
            throw new JsonException("Unexpected end of input at byte " + pos);
        final byte[] inp = input;
        final int w = (inp[pos]     & 0xFF)
            | ((inp[pos + 1] & 0xFF) << 8)
            | ((inp[pos + 2] & 0xFF) << 16)
            | ((inp[pos + 3] & 0xFF) << 24);
        if (w != 0x65757274)
            throw new JsonException("Invalid literal at byte " + pos);
        pos += 4;
        return JsonBoolean.of(true);
    }

    private JsonValue consumeLiteralFalse() {
        if (pos + 5 > limit)
            throw new JsonException("Unexpected end of input at byte " + pos);
        final byte[] inp = input;
        final int w = (inp[pos]     & 0xFF)
            | ((inp[pos + 1] & 0xFF) << 8)
            | ((inp[pos + 2] & 0xFF) << 16)
            | ((inp[pos + 3] & 0xFF) << 24);
        if (w != 0x736C6166 || inp[pos + 4] != 'e')
            throw new JsonException("Invalid literal at byte " + pos);
        pos += 5;
        return JsonBoolean.of(false);
    }

    private JsonValue consumeLiteralNull() {
        if (pos + 4 > limit)
            throw new JsonException("Unexpected end of input at byte " + pos);
        final byte[] inp = input;
        final int w = (inp[pos]     & 0xFF)
            | ((inp[pos + 1] & 0xFF) << 8)
            | ((inp[pos + 2] & 0xFF) << 16)
            | ((inp[pos + 3] & 0xFF) << 24);
        if (w != 0x6C6C756E)
            throw new JsonException("Invalid literal at byte " + pos);
        pos += 4;
        return JsonNull.INSTANCE;
    }

    private JsonNumber parseNumber() {
        final int    tokenStart = pos;
        final int    tokenLen   = findValueLength(tokenStart);
        final byte[] inp        = input;
        final byte   lastByte   = inp[tokenStart + tokenLen - 1];
        final int    payloadLen = tokenLen - 1;
        switch (lastByte) {
            case 'b', 'B' -> { pos += tokenLen; return new JsonByte((byte)  parseInt(tokenStart, payloadLen)); }
            case 's', 'S' -> { pos += tokenLen; return new JsonShort((short) parseInt(tokenStart, payloadLen)); }
            case 'l', 'L' -> { pos += tokenLen; return new JsonLong(         parseLong(tokenStart, payloadLen)); }
            case 'f', 'F' -> { pos += tokenLen; return new JsonFloat((float)  parseDouble(tokenStart, payloadLen)); }
            case 'd', 'D' -> { pos += tokenLen; return new JsonDouble(        parseDouble(tokenStart, payloadLen)); }
        }
        pos += tokenLen;
        if (TOKEN[lastByte & 0xFF] == TOK_FLOAT_SIG)
            return new JsonDouble(parseDouble(tokenStart, tokenLen));
        final int tokenEnd = tokenStart + tokenLen - 1;
        for (int i = tokenStart; i < tokenEnd; i++) {
            if (TOKEN[inp[i] & 0xFF] == TOK_FLOAT_SIG)
                return new JsonDouble(parseDouble(tokenStart, tokenLen));
        }
        final long raw = parseLong(tokenStart, tokenLen);
        return (raw >= Integer.MIN_VALUE && raw <= Integer.MAX_VALUE)
            ? new JsonInteger((int) raw)
            : new JsonLong(raw);
    }

    @SuppressWarnings("ObjectAllocationInLoop")
    private JsonObject parseObjectIterative() {
        if (pos >= limit || TOKEN[input[pos] & 0xFF] != TOK_OBJ_OPEN)
            throw new JsonException("Expected '{' at byte " + pos);
        pos++;
        skipWs();
        final JsonObject obj = new JsonObject();
        while (nextField()) {
            final String key = fieldNameAsString();
            obj.put(key, parseIterative(fieldValueStart));
        }
        return obj;
    }

    private JsonArray parseArrayIterative() {
        if (pos >= limit || TOKEN[input[pos] & 0xFF] != TOK_ARR_OPEN)
            throw new JsonException("Expected '[' at byte " + pos);
        pos++;
        skipWs();
        final JsonArray arr = new JsonArray();
        while (true) {
            skipWs();
            if (pos >= limit) throw new JsonException("Unterminated array");
            if (TOKEN[input[pos] & 0xFF] == TOK_ARR_CLOSE) {
                pos++;
                break;
            }
            if (!nextElement()) throw new JsonException("Expected element at byte " + pos);
            JsonValue jsonValue = parseIterative(elementValueStart);
            arr.add(jsonValue);
        }
        return arr;
    }

    private JsonValue parseIterative(int elementValueStart) {
        final int    savedPos = elementValueStart;
        final JsonValue jsonValue;
        final byte vTok = TOKEN[input[savedPos] & 0xFF];
        if (vTok == TOK_OBJ_OPEN || vTok == TOK_ARR_OPEN) {
            final int        vEnd = savedPos + findValueLength(savedPos);
            final JsonCursor sub  = new JsonCursor(input, scan, savedPos, vEnd, this);
            jsonValue = sub.parseValueInternal();
            pos = vEnd;
        } else {
            pos       = savedPos;
            jsonValue = parseValueInternal();
        }
        skipWs();
        if (pos < limit && TOKEN[input[pos] & 0xFF] == TOK_COMMA) pos++;
        return jsonValue;
    }
}