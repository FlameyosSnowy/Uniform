package me.flame.uniform.json.writers;

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
        write(bytes, 0, bytes.length);
    }

    private void write(byte[] bytes, int off, int len) {
        ensure(len);
        System.arraycopy(bytes, off, buf, pos, len);
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
        ensure(len);

        for (int i = 0; i < len; i++)
            buf[pos++] = (byte) s.charAt(i);
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
}