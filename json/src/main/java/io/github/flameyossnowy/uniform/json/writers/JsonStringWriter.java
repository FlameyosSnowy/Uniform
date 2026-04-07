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

    public void write(byte b) {
        ensure(1);
        buf[pos++] = b;
    }

    public void write(char b) {
        ensure(1);
        buf[pos++] = (byte) b;
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
        commaIfNeeded();
        write(NULL);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(boolean v) {
        commaIfNeeded();
        write(v ? TRUE : FALSE);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(int v) {
        commaIfNeeded();
        writeAscii(Integer.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(long v) {
        commaIfNeeded();
        writeAscii(Long.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(double v) {
        commaIfNeeded();
        writeAscii(Double.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(float v) {
        commaIfNeeded();
        writeAscii(Float.toString(v));
        needComma = true;
        return this;
    }

    public JsonStringWriter value(Number v) {
        if (v == null) return nullValue();
        commaIfNeeded();
        writeAscii(v.toString());
        needComma = true;
        return this;
    }

    public JsonStringWriter value(String v) {
        commaIfNeeded();
        if (v == null) {
            write(NULL);
            needComma = true;
            return this;
        }
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

    public void writeString(String s) {
        final int len = s.length();
        ensure(len * 6 + 2);
        buf[pos++] = '"';

        int safe = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 128 || !NEEDS_ESCAPE[c]) continue;

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
     * Writes {@code bytes} directly into the output buffer and automatically
     * participates in the comma protocol by inspecting the last byte:
     * <ul>
     *   <li>Last byte is {@code ':'} → treated as a field-name fragment
     *       (e.g. {@code "fieldName":}). A comma is prepended when needed and
     *       {@code needComma} is cleared so the following value does not add
     *       a spurious comma.
     *   <li>Any other last byte → treated as a value fragment. A comma is
     *       prepended when needed and {@code needComma} is set to {@code true}.
     * </ul>
     */
    public void writeRaw(byte[] bytes) {
        final boolean isName = bytes.length > 0 && bytes[bytes.length - 1] == ':';
        commaIfNeeded();
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
        needComma = !isName;
    }

    /**
     * Writes {@code len} bytes from {@code bytes[off..off+len)}.
     * Same comma-protocol rules as {@link #writeRaw(byte[])}: if the last byte
     * is {@code ':'} the fragment is treated as a field name; otherwise as a value.
     */
    public void writeRaw(byte[] bytes, int off, int len) {
        final boolean isName = len > 0 && bytes[off + len - 1] == ':';
        commaIfNeeded();
        ensure(len);
        System.arraycopy(bytes, off, buf, pos, len);
        pos += len;
        needComma = !isName;
    }

    /**
     * Writes a pre-encoded field name fragment (e.g. {@code "fieldName":}) directly
     * into the output buffer. Handles comma insertion before the name, then resets
     * {@code needComma} to false since the trailing colon means a value follows next.
     */
    public void writeRawName(byte[] bytes) {
        commaIfNeeded();
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
        needComma = false;
    }

    /**
     * Writes a pre-encoded value fragment directly into the output buffer.
     * Handles comma insertion and marks that the next sibling will need a comma.
     */
    public void writeRawValue(byte[] bytes) {
        commaIfNeeded();
        ensure(bytes.length);
        System.arraycopy(bytes, 0, buf, pos, bytes.length);
        pos += bytes.length;
        needComma = true;
    }

    /**
     * Writes a slice of a pre-encoded value fragment.
     */
    public void writeRawValue(byte[] bytes, int off, int len) {
        commaIfNeeded();
        ensure(len);
        System.arraycopy(bytes, off, buf, pos, len);
        pos += len;
        needComma = true;
    }

    // Two-digit lookup table
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
        commaIfNeeded();
        ensure(11);
        if (value == Integer.MIN_VALUE) {
            System.arraycopy(MIN_INT_BYTES, 0, buf, pos, MIN_INT_BYTES.length);
            pos += MIN_INT_BYTES.length;
            needComma = true;
            return;
        }
        if (value < 0) { buf[pos++] = '-'; value = -value; }
        pos = writePositiveInt(value, buf, pos);
        needComma = true;
    }

    /**
     * Writes a {@code long} value as ASCII decimal digits directly into the buffer.
     * Never allocates a String. Handles all values including {@link Long#MIN_VALUE}.
     */
    public void writeLong(long value) {
        commaIfNeeded();
        ensure(20);
        if (value == Long.MIN_VALUE) {
            System.arraycopy(MIN_LONG_BYTES, 0, buf, pos, MIN_LONG_BYTES.length);
            pos += MIN_LONG_BYTES.length;
            needComma = true;
            return;
        }
        if (value < 0) { buf[pos++] = '-'; value = -value; }
        pos = writePositiveLong(value, buf, pos);
        needComma = true;
    }

    private static int writePositiveInt(int v, byte[] buf, int pos) {
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

    private static final ThreadLocal<byte[]> FP_BUF =
        ThreadLocal.withInitial(() -> new byte[25]);

    /**
     * Writes a {@code double} value as ASCII decimal into the buffer.
     * Handles comma insertion and special values (NaN, Infinity, 0.0).
     */
    public void writeDouble(double value) {
        commaIfNeeded();
        if (value == 0.0) {
            ensure(4);
            if (1.0 / value == Double.NEGATIVE_INFINITY) {
                buf[pos++] = '-';
            }
            buf[pos++] = '0';
            buf[pos++] = '.';
            buf[pos++] = '0';
            needComma = true;
            return;
        }
        if (Double.isNaN(value)) {
            ensure(3);
            buf[pos++] = 'N'; buf[pos++] = 'a'; buf[pos++] = 'N';
            needComma = true;
            return;
        }
        if (value == Double.POSITIVE_INFINITY) {
            ensure(8);
            System.arraycopy(BYTES_INFINITY, 0, buf, pos, 8);
            pos += 8;
            needComma = true;
            return;
        }
        if (value == Double.NEGATIVE_INFINITY) {
            ensure(9);
            System.arraycopy(BYTES_NEG_INFINITY, 0, buf, pos, 9);
            pos += 9;
            needComma = true;
            return;
        }
        final String s = Double.toString(value);
        final int    n = s.length();
        ensure(n);
        //noinspection deprecation
        s.getBytes(0, n, buf, pos);
        pos += n;
        needComma = true;
    }

    /**
     * Writes a {@code float} value. See {@link #writeDouble} for notes.
     */
    public void writeFloat(float value) {
        commaIfNeeded();
        if (value == 0.0f) {
            ensure(4);
            if (1.0f / value == Float.NEGATIVE_INFINITY) {
                buf[pos++] = '-';
            }
            buf[pos++] = '0';
            buf[pos++] = '.';
            buf[pos++] = '0';
            needComma = true;
            return;
        }
        if (Float.isNaN(value)) {
            ensure(3);
            buf[pos++] = 'N'; buf[pos++] = 'a'; buf[pos++] = 'N';
            needComma = true;
            return;
        }
        if (value == Float.POSITIVE_INFINITY) {
            ensure(8);
            System.arraycopy(BYTES_INFINITY, 0, buf, pos, 8);
            pos += 8;
            needComma = true;
            return;
        }
        if (value == Float.NEGATIVE_INFINITY) {
            ensure(9);
            System.arraycopy(BYTES_NEG_INFINITY, 0, buf, pos, 9);
            pos += 9;
            needComma = true;
            return;
        }
        final String s = Float.toString(value);
        final int    n = s.length();
        ensure(n);
        //noinspection deprecation
        s.getBytes(0, n, buf, pos);
        pos += n;
        needComma = true;
    }

    /**
     * Writes a quoted JSON string value with an ASCII fast-path.
     * Handles comma insertion before the value.
     */
    @SuppressWarnings("deprecation")
    public void writeQuotedAsciiOrUtf8(String s) {
        commaIfNeeded();
        if (s == null) {
            ensure(4);
            System.arraycopy(NULL, 0, buf, pos, 4);
            pos += 4;
            needComma = true;
            return;
        }
        final int len = s.length();

        boolean fast = true;
        int i = 0;
        final int limit4 = len - 3;
        while (i < limit4) {
            final char c0 = s.charAt(i), c1 = s.charAt(i+1),
                c2 = s.charAt(i+2), c3 = s.charAt(i+3);
            if (((c0 | c1 | c2 | c3) & 0xFF80) != 0) { fast = false; break; }
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
            ensure(len + 2);
            buf[pos++] = '"';
            s.getBytes(0, len, buf, pos);
            pos += len;
            buf[pos++] = '"';
        } else {
            writeString(s);
        }
        needComma = true;
    }

    /**
     * Writes a quoted JSON object key (field name) from a runtime String with an
     * ASCII fast-path. Output is {@code "key":} (colon included).
     * Handles comma insertion and resets {@code needComma} to false so the
     * following value does not emit a spurious comma.
     */
    @SuppressWarnings("deprecation")
    public void writeQuotedNameAsciiOrUtf8(String key) {
        if (key == null) return; // malformed map - skip
        commaIfNeeded();
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
            key.getBytes(0, len, buf, pos);
            pos += len;
            buf[pos++] = '"';
            buf[pos++] = ':';
        } else {
            name(key); // handles encoding + escaping, sets needComma=false itself
            return;
        }
        needComma = false; // colon written; value follows next
    }
}