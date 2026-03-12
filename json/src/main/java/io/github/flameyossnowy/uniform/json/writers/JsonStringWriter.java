package io.github.flameyossnowy.uniform.json.writers;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@SuppressWarnings({ "unused", "UnusedReturnValue" })
public sealed class JsonStringWriter permits JsonDomBuilder {
    private byte[] buf;
    private int pos;

    private byte[] ctxStack = new byte[8];
    private int depth = 0;
    private boolean needComma = false;

    private static final byte CTX_OBJECT = 1;
    private static final byte CTX_ARRAY  = 2;

    private static final boolean[] NEEDS_ESCAPE = new boolean[128];
    static {
        NEEDS_ESCAPE['"']  = true;
        NEEDS_ESCAPE['\\'] = true;
        for (int i = 0; i < 0x20; i++) NEEDS_ESCAPE[i] = true;
    }

    private static final byte[] HEX    = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final byte[] NULL   = "null".getBytes(StandardCharsets.UTF_8);
    private static final byte[] TRUE   = "true".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FALSE  = "false".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_QUOTE     = "\\\"".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_BACKSLASH = "\\\\".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_NEWLINE   = "\\n".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_CR        = "\\r".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_TAB       = "\\t".getBytes(StandardCharsets.UTF_8);
    private static final byte[] ESC_UNICODE   = "\\u00".getBytes(StandardCharsets.UTF_8);
    private static final byte[] BYTES_INFINITY     = {'I','n','f','i','n','i','t','y'};
    private static final byte[] BYTES_NEG_INFINITY = {'-','I','n','f','i','n','i','t','y'};

    public JsonStringWriter() {
        this.buf = new byte[128];
    }

    public JsonStringWriter(int capacity) {
        this.buf = new byte[capacity];
    }

    private void ensure(int extra) {
        if (pos + extra > buf.length)
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, pos + extra));
    }

    private void write(byte b) {
        ensure(1);
        buf[pos++] = b;
    }

    private void write(byte[] bytes) {
        write(bytes, bytes.length);
    }

    private void write(byte[] bytes, int off, int len) {
        ensure(len);
        System.arraycopy(bytes, off, buf, pos, len);
        pos += len;
    }

    private void write(byte[] bytes, int len) {
        ensure(len);
        System.arraycopy(bytes, 0, buf, pos, len);
        pos += len;
    }

    private void push(byte ctx) {
        if (depth == ctxStack.length) {
            byte[] grown = new byte[ctxStack.length * 2];
            System.arraycopy(ctxStack, 0, grown, 0, ctxStack.length);
            ctxStack = grown;
        }
        ctxStack[depth++] = ctx;
        needComma = false;
    }

    private void pop(byte expected) {
        if (depth == 0) return;
        if (ctxStack[--depth] != expected)
            throw new IllegalStateException("Mismatched JSON container close");
        needComma = true;
    }

    private void commaIfNeeded() {
        if (needComma) write((byte) ',');
    }

    private void requireArrayContext() {
        if (depth == 0 || ctxStack[depth - 1] != CTX_ARRAY)
            throw new IllegalStateException("Not in array context");
    }

    public JsonStringWriter reset() {
        pos = 0;
        needComma = false;
        depth = 0;
        return this;
    }

    public JsonStringWriter beginObject() {
        if (depth > 0 && ctxStack[depth - 1] == CTX_ARRAY) commaIfNeeded();
        push(CTX_OBJECT);
        write((byte) '{');
        return this;
    }

    public JsonStringWriter endObject() {
        write((byte) '}');
        pop(CTX_OBJECT);
        return this;
    }

    public JsonStringWriter beginArray() {
        if (depth > 0 && ctxStack[depth - 1] == CTX_ARRAY) commaIfNeeded();
        push(CTX_ARRAY);
        write((byte) '[');
        return this;
    }

    public JsonStringWriter endArray() {
        write((byte) ']');
        pop(CTX_ARRAY);
        return this;
    }

    public JsonStringWriter name(String name) {
        commaIfNeeded();
        writeString(name);
        write((byte) ':');
        needComma = false;
        return this;
    }

    public JsonStringWriter nameAscii(String name) {
        commaIfNeeded();
        write((byte) '"');
        writeAscii(name);
        write((byte) '"');
        write((byte) ':');
        needComma = false;
        return this;
    }

    public JsonStringWriter nameAscii(byte[] bytes) {
        commaIfNeeded();
        write((byte) '"');
        writeAscii(bytes);
        write((byte) '"');
        write((byte) ':');
        needComma = false;
        return this;
    }

    public JsonStringWriter nullValue() {
        write(NULL);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(boolean v) {
        write(v ? TRUE : FALSE);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(int v) {
        writeAscii(Integer.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(long v) {
        writeAscii(Long.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(double v) {
        writeAscii(Double.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(float v) {
        writeAscii(Float.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(Number v) {
        if (v == null) return nullValue();
        writeAscii(v.toString());
        needComma = true;
        return this;
    }

    public JsonStringWriter value(String v) {
        if (v == null) return nullValue();
        writeString(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayNullValue() {
        requireArrayContext();
        commaIfNeeded();
        write(NULL);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(boolean v) {
        requireArrayContext();
        commaIfNeeded();
        write(v ? TRUE : FALSE);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(int v) {
        requireArrayContext();
        commaIfNeeded();
        writeAscii(Integer.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(long v) {
        requireArrayContext();
        commaIfNeeded();
        writeAscii(Long.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(double v) {
        requireArrayContext();
        commaIfNeeded();
        writeAscii(Double.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(float v) {
        requireArrayContext();
        commaIfNeeded();
        writeAscii(Float.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(Number v) {
        if (v == null) return arrayNullValue();
        requireArrayContext();
        commaIfNeeded();
        writeAscii(v.toString());
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(String v) {
        if (v == null) return arrayNullValue();
        requireArrayContext();
        commaIfNeeded();
        writeString(v);
        needComma = true;
        return this;
    }

    public String finish() {
        return new String(buf, 0, pos, StandardCharsets.UTF_8);
    }

    public byte[] finishAsBytes() {
        return Arrays.copyOf(buf, pos);
    }

    private void writeString(String s) {
        final int len = s.length();
        // Worst case: every char becomes uXXXX (6 bytes) + 2 quotes
        ensure(len * 6 + 2);
        buf[pos++] = '"';

        int safe = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 128 || !NEEDS_ESCAPE[c]) continue;

            // Flush safe ASCII prefix as UTF-8
            if (i > safe) {
                writeUtf8(s, safe, i);
            }

            switch (c) {
                case '"'  -> write(ESC_QUOTE);
                case '\\' -> write(ESC_BACKSLASH);
                case '\n' -> write(ESC_NEWLINE);
                case '\r' -> write(ESC_CR);
                case '\t' -> write(ESC_TAB);
                default   -> {
                    write(ESC_UNICODE);
                    write(HEX[(c >> 4) & 0xF]);
                    write(HEX[c & 0xF]);
                }
            }
            safe = i + 1;
        }

        if (safe < len) {
            writeUtf8(s, safe, len);
        }

        buf[pos++] = '"';
    }

    private void writeAscii(String s) {
        final int len = s.length();
        ensure(pos + len);
        //noinspection deprecation
        s.getBytes(0, len, buf, pos);
        this.pos += len;
    }

    private void writeAscii(byte[] s) {
        ensure(s.length);
        System.arraycopy(s, 0, buf, pos, s.length);
        pos += s.length;
    }

    private void writeUtf8(String s, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = s.charAt(i);
            if (c < 0x80) {
                buf[pos++] = (byte) c;
            } else if (c < 0x800) {
                ensure(2);
                buf[pos++] = (byte) (0xC0 | (c >> 6));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else if (c < 0xD800 || c > 0xDFFF) {
                ensure(3);
                buf[pos++] = (byte) (0xE0 | (c >> 12));
                buf[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (c & 0x3F));
            } else {
                // Surrogate pair -> 4-byte UTF-8
                ensure(4);
                int cp = Character.toCodePoint(c, s.charAt(++i));
                buf[pos++] = (byte) (0xF0 | (cp >> 18));
                buf[pos++] = (byte) (0x80 | ((cp >> 12) & 0x3F));
                buf[pos++] = (byte) (0x80 | ((cp >> 6) & 0x3F));
                buf[pos++] = (byte) (0x80 | (cp & 0x3F));
            }
        }
    }

    /**
     * Writes {@code bytes} directly into the output buffer with a single
     * {@link System#arraycopy}. No encoding, no escaping.
     * Used for pre-encoded field name fragments and literal constants.
     */
    public void writeRaw(byte[] bytes) {
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
    }

    /**
     * Writes {@code len} bytes from {@code bytes[off..off+len)} into the buffer.
     * Used when only a slice of a pre-allocated array is valid (e.g. Ryu output).
     */
    public void writeRaw(byte[] bytes, int off, int len) {
        ensure(len);
        System.arraycopy(bytes, off, buf, pos, len);
        pos += len;
    }

    // Two-digit lookup table - one read gets two digits, eliminating division
    // in the common case.
    private static final byte[] DIGIT_PAIRS;
    static {
        DIGIT_PAIRS = new byte[200];
        for (int i = 0; i < 100; i++) {
            DIGIT_PAIRS[i * 2]     = (byte) ('0' + i / 10);
            DIGIT_PAIRS[i * 2 + 1] = (byte) ('0' + i % 10);
        }
    }

    private static final byte[] MIN_INT_BYTES  =
        "-2147483648".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    private static final byte[] MIN_LONG_BYTES =
        "-9223372036854775808".getBytes(java.nio.charset.StandardCharsets.US_ASCII);

    /**
     * Writes an {@code int} value as ASCII decimal digits directly into the buffer.
     * Never allocates a String. Handles all values including {@link Integer#MIN_VALUE}.
     */
    public void writeInt(int value) {
        ensure(11); // max: "-2147483648" = 11 bytes
        if (value == Integer.MIN_VALUE) {
            System.arraycopy(MIN_INT_BYTES, 0, buf, pos, MIN_INT_BYTES.length);
            pos += MIN_INT_BYTES.length;
            return;
        }
        if (value < 0) { buf[pos++] = '-'; value = -value; }
        pos = writePositiveInt(value, buf, pos);
    }

    /**
     * Writes a {@code long} value as ASCII decimal digits directly into the buffer.
     * Never allocates a String. Handles all values including {@link Long#MIN_VALUE}.
     */
    public void writeLong(long value) {
        ensure(20); // max: "-9223372036854775808" = 20 bytes
        if (value == Long.MIN_VALUE) {
            System.arraycopy(MIN_LONG_BYTES, 0, buf, pos, MIN_LONG_BYTES.length);
            pos += MIN_LONG_BYTES.length;
            return;
        }
        if (value < 0) { buf[pos++] = '-'; value = -value; }
        pos = writePositiveLong(value, buf, pos);
    }

    private static int writePositiveInt(int v, byte[] buf, int pos) {
        // Unrolled for the most common cases (1–4 digits cover the majority of
        // integer fields: ages, counts, IDs, status codes, etc.)
        if (v < 10) {
            buf[pos] = (byte) ('0' + v);
            return pos + 1;
        }
        if (v < 100) {
            final int d = v * 2;
            buf[pos]   = DIGIT_PAIRS[d];
            buf[pos+1] = DIGIT_PAIRS[d+1];
            return pos + 2;
        }
        if (v < 1000) {
            buf[pos]   = (byte) ('0' + v / 100);
            final int r = (v % 100) * 2;
            buf[pos+1] = DIGIT_PAIRS[r];
            buf[pos+2] = DIGIT_PAIRS[r+1];
            return pos + 3;
        }
        if (v < 10000) {
            final int hi = v / 100, lo = v % 100;
            buf[pos]   = DIGIT_PAIRS[hi * 2];
            buf[pos+1] = DIGIT_PAIRS[hi * 2 + 1];
            buf[pos+2] = DIGIT_PAIRS[lo * 2];
            buf[pos+3] = DIGIT_PAIRS[lo * 2 + 1];
            return pos + 4;
        }
        // General case: write right-to-left into a 10-byte local scratch area
        // that sits on the stack (JIT will typically eliminate the allocation).
        final byte[] tmp = new byte[10];
        int end = 10;
        while (v >= 100) {
            final int r = (v % 100) * 2;
            v /= 100;
            tmp[--end] = DIGIT_PAIRS[r + 1];
            tmp[--end] = DIGIT_PAIRS[r];
        }
        if (v >= 10) {
            tmp[--end] = DIGIT_PAIRS[v * 2 + 1];
            tmp[--end] = DIGIT_PAIRS[v * 2];
        } else {
            tmp[--end] = (byte) ('0' + v);
        }
        final int written = 10 - end;
        System.arraycopy(tmp, end, buf, pos, written);
        return pos + written;
    }

    private static int writePositiveLong(long v, byte[] buf, int pos) {
        // Delegate to int path when safe - avoids the 64-bit division loop
        // for the vast majority of long fields (timestamps aside).
        if (v <= Integer.MAX_VALUE) return writePositiveInt((int) v, buf, pos);
        final byte[] tmp = new byte[19];
        int end = 19;
        while (v >= 100) {
            final int r = (int) ((v % 100) * 2);
            v /= 100;
            tmp[--end] = DIGIT_PAIRS[r + 1];
            tmp[--end] = DIGIT_PAIRS[r];
        }
        if (v >= 10) {
            tmp[--end] = DIGIT_PAIRS[(int) (v * 2 + 1)];
            tmp[--end] = DIGIT_PAIRS[(int) (v * 2)];
        } else {
            tmp[--end] = (byte) ('0' + v);
        }
        final int written = 19 - end;
        System.arraycopy(tmp, end, buf, pos, written);
        return pos + written;
    }
    
    // Thread-local 25-byte scratch buffer for double/float digit output.
    // 25 bytes covers the longest possible double string ("−1.7976931348623157E+308" = 24 chars).
    private static final ThreadLocal<byte[]> FP_BUF =
        ThreadLocal.withInitial(() -> new byte[25]);

    /**
     * Writes a {@code double} value as ASCII decimal into the buffer.
     * For special values (NaN, Infinity, 0.0) this is branch-free and allocation-free.
     * For general values, uses {@code Double.toString} but writes directly into the
     * output buffer via the deprecated ASCII-only {@code String.getBytes(int,int,byte[],int)}
     * - which is safe here because {@code Double.toString} always produces ASCII output.
     */
    public void writeDouble(double value) {
        // Special cases first: covers a meaningful fraction of real JSON doubles
        if (value == 0.0) {
            ensure(4); // "0.0" or "-0.0"
            if (1.0 / value == Double.NEGATIVE_INFINITY) {
                buf[pos++] = '-';
            }
            buf[pos++] = '0';
            buf[pos++] = '.';
            buf[pos++] = '0';
            return;
        }
        if (Double.isNaN(value)) {
            ensure(3);
            buf[pos++] = 'N'; buf[pos++] = 'a'; buf[pos++] = 'N';
            return;
        }
        // General case: Double.toString always produces ASCII, so we can use the
        // deprecated getBytes(int,int,byte[],int) safely - it's an ASCII-only cast.
        // This avoids both a char[] allocation and a UTF-8 encoding pass.
        final String s = Double.toString(value);
        final int    n = s.length();
        ensure(n);
        //noinspection deprecation
        s.getBytes(0, n, buf, pos); // safe: Double.toString is always ASCII
        pos += n;
    }

    /**
     * Writes a {@code float} value. See {@link #writeDouble} for notes.
     */
    public void writeFloat(float value) {
        if (value == 0.0f) {
            ensure(4);
            if (1.0f / value == Float.NEGATIVE_INFINITY) {
                buf[pos++] = '-';
            }
            buf[pos++] = '0';
            buf[pos++] = '.';
            buf[pos++] = '0';
            return;
        }
        if (Float.isNaN(value)) {
            writeDouble(Double.NaN);
            return;
        }
        if (value == Double.POSITIVE_INFINITY) {
            final byte[] b =  BYTES_INFINITY;
            ensure(8);
            System.arraycopy(b, 0, buf, pos, 8);
            pos += 8;
            return;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            final byte[] b = BYTES_NEG_INFINITY;
            ensure(9);
            System.arraycopy(b, 0, buf, pos, 9);
            pos += 9;

            writeDouble(Double.NEGATIVE_INFINITY);
            return;
        }
        final String s = Float.toString(value);
        final int    n = s.length();
        ensure(n);
        //noinspection deprecation
        s.getBytes(0, n, buf, pos); // safe: Float.toString is always ASCII
        pos += n;
    }

    /**
     * Writes a quoted JSON string value with an ASCII fast-path.
     * <p>
     * Scans the string in 4-char SWAR chunks for any byte above 0x7F or any
     * character requiring JSON escaping ({@code "}, {@code \}, control chars).
     * If the string is pure printable ASCII with no JSON-special chars, it is
     * written with a single {@link System#arraycopy} after opening/closing quotes.
     * Otherwise, falls back to {@link #value(String)} which handles full UTF-8
     * encoding and escape sequences.
     *
     * @param s the string to write; must not be null (caller checks)
     */
    @SuppressWarnings("deprecation")
    public void writeQuotedAsciiOrUtf8(String s) {
        if (s == null) { writeRaw(new byte[]{'n','u','l','l'}); return; }
        final int len = s.length();

        // Phase 1: scan for anything that requires slow-path handling.
        // We check 4 chars at a time by OR-ing into a single int and testing:
        //   - bit 15+ set  => non-ASCII (char > 0x7F)
        //   - any char == '"' (0x22) or '\\' (0x5C) or < 0x20 (control)
        boolean fast = true;
        int i = 0;
        final int limit4 = len - 3;
        while (i < limit4) {
            final char c0 = s.charAt(i), c1 = s.charAt(i+1),
                c2 = s.charAt(i+2), c3 = s.charAt(i+3);
            // Non-ASCII check: any char >= 0x80
            if (((c0 | c1 | c2 | c3) & 0xFF80) != 0) { fast = false; break; }
            // Escape check: '"', '\\', or control char < 0x20
            if (c0 == '"' || c0 == '\\' || c0 < 0x20) { fast = false; break; }
            if (c1 == '"' || c1 == '\\' || c1 < 0x20) { fast = false; break; }
            if (c2 == '"' || c2 == '\\' || c2 < 0x20) { fast = false; break; }
            if (c3 == '"' || c3 == '\\' || c3 < 0x20) { fast = false; break; }
            i += 4;
        }
        if (fast) {
            while (i < len) {
                final char c = s.charAt(i);
                if ((c & 0xFF80) != 0 || c == '"' || c == '\\' || c < 0x20) { fast = false; break; }
                i++;
            }
        }

        if (fast) {
            // Pure printable ASCII, no escaping needed: write as "...\0"
            ensure(len + 2);
            buf[pos++] = '"';
            // Deprecated String.getBytes is safe here: we've verified all chars are ASCII.
            s.getBytes(0, len, buf, pos);
            pos += len;
            buf[pos++] = '"';
        } else {
            // Non-ASCII or contains escape-requiring chars: full encoding path
            value(s);
        }
    }

    /**
     * Writes a quoted JSON object key (field name) from a runtime String with an
     * ASCII fast-path. Used for Map<String, V> keys that are not known at codegen time.
     * The output is {@code "key":} (colon included) to match pre-encoded field fragments.
     */
    @SuppressWarnings("deprecation")
    public void writeQuotedNameAsciiOrUtf8(String key) {
        if (key == null) return; // malformed map - skip
        final int len = key.length();

        boolean fast = true;
        int i = 0;
        final int limit4 = len - 3;
        while (i < limit4) {
            final char c0 = key.charAt(i), c1 = key.charAt(i+1),
                c2 = key.charAt(i+2), c3 = key.charAt(i+3);
            if (((c0 | c1 | c2 | c3) & 0xFF80) != 0) { fast = false; break; }
            if (c0 == '"' || c0 == '\\' || c0 < 0x20) { fast = false; break; }
            if (c1 == '"' || c1 == '\\' || c1 < 0x20) { fast = false; break; }
            if (c2 == '"' || c2 == '\\' || c2 < 0x20) { fast = false; break; }
            if (c3 == '"' || c3 == '\\' || c3 < 0x20) { fast = false; break; }
            i += 4;
        }
        if (fast) {
            while (i < len) {
                final char c = key.charAt(i);
                if ((c & 0xFF80) != 0 || c == '"' || c == '\\' || c < 0x20) { fast = false; break; }
                i++;
            }
        }

        if (fast) {
            ensure(len + 3); // "key":
            buf[pos++] = '"';
            key.getBytes(0, len, buf, pos); // safe: verified ASCII
            pos += len;
            buf[pos++] = '"';
            buf[pos++] = ':';
        } else {
            // Non-ASCII key: fall back to name() which handles encoding + escaping
            name(key);
        }
    }
}