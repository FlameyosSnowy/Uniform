package me.flame.uniform.json.writers;

public final class JsonStringWriter {
    private final StringBuilder out;
    private boolean needComma = false;

    private byte[] ctxStack = new byte[8];
    private int depth = 0;

    private static final byte CTX_OBJECT = 1;
    private static final byte CTX_ARRAY  = 2;

    // Lookup table: true = needs escaping
    private static final boolean[] NEEDS_ESCAPE = new boolean[128];
    static {
        NEEDS_ESCAPE['"']  = true;
        NEEDS_ESCAPE['\\'] = true;
        for (int i = 0; i < 0x20; i++) NEEDS_ESCAPE[i] = true; // control chars
    }

    public JsonStringWriter() {
        this.out = new StringBuilder(128);
    }

    public JsonStringWriter(int capacity) {
        this.out = new StringBuilder(capacity);
    }

    // ── context stack ────────────────────────────────────────────────────────

    private void push(byte ctx) {
        if (depth == ctxStack.length) {
            byte[] nctx = new byte[ctxStack.length * 2];
            System.arraycopy(ctxStack, 0, nctx, 0, ctxStack.length);
            ctxStack = nctx;
        }
        ctxStack[depth++] = ctx;
        needComma = false;
    }

    private void pop(byte expected) {
        if (depth == 0) return;
        depth--;
        needComma = true;
        if (ctxStack[depth] != expected)
            throw new IllegalStateException("Mismatched JSON container close");
    }

    private void commaIfNeeded() {
        if (needComma) out.append(',');
    }

    private void requireArrayContext() {
        if (depth == 0 || ctxStack[depth - 1] != CTX_ARRAY)
            throw new IllegalStateException("Not in array context");
    }

    // ── structural ───────────────────────────────────────────────────────────

    public JsonStringWriter reset() {
        out.setLength(0);
        needComma = false;
        depth = 0;
        return this;
    }

    public JsonStringWriter beginObject() {
        if (depth > 0 && ctxStack[depth - 1] == CTX_ARRAY) commaIfNeeded();
        push(CTX_OBJECT);
        out.append('{');
        return this;
    }

    public JsonStringWriter endObject() {
        out.append('}');
        pop(CTX_OBJECT);
        return this;
    }

    public JsonStringWriter beginArray() {
        if (depth > 0 && ctxStack[depth - 1] == CTX_ARRAY) commaIfNeeded();
        push(CTX_ARRAY);
        out.append('[');
        return this;
    }

    public JsonStringWriter endArray() {
        out.append(']');
        pop(CTX_ARRAY);
        return this;
    }

    // ── field names ──────────────────────────────────────────────────────────

    /** Use when field name may contain characters needing escaping. */
    public JsonStringWriter name(String name) {
        commaIfNeeded();
        writeString(name);
        out.append(':');
        needComma = false;
        return this;
    }

    /** Use when field name is a compile-time ASCII literal (no escaping needed). */
    public JsonStringWriter nameAscii(String name) {
        commaIfNeeded();
        out.append('"').append(name).append('"').append(':');
        needComma = false;
        return this;
    }

    // ── object values ────────────────────────────────────────────────────────

    public JsonStringWriter nullValue() {
        out.append("null");
        needComma = true;
        return this;
    }

    public JsonStringWriter value(boolean v) {
        out.append(v ? "true" : "false");
        needComma = true;
        return this;
    }

    public JsonStringWriter value(int v) {
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(long v) {
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(double v) {
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(float v) {
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(Number v) {
        if (v == null) return nullValue();
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter value(String v) {
        if (v == null) return nullValue();
        writeString(v);
        needComma = true;
        return this;
    }

    // ── array values ─────────────────────────────────────────────────────────

    public JsonStringWriter arrayNullValue() {
        requireArrayContext();
        commaIfNeeded();
        out.append("null");
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(boolean v) {
        requireArrayContext();
        commaIfNeeded();
        out.append(v ? "true" : "false");
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(int v) {
        requireArrayContext();
        commaIfNeeded();
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(long v) {
        requireArrayContext();
        commaIfNeeded();
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(double v) {
        requireArrayContext();
        commaIfNeeded();
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(float v) {
        requireArrayContext();
        commaIfNeeded();
        out.append(v);
        needComma = true;
        return this;
    }

    public JsonStringWriter arrayValue(Number v) {
        if (v == null) return arrayNullValue();
        requireArrayContext();
        commaIfNeeded();
        out.append(v);
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

    // ── finish ───────────────────────────────────────────────────────────────

    public String finish() {
        return out.toString();
    }

    // ── string escaping ──────────────────────────────────────────────────────

    private void writeString(String s) {
        final int len = s.length();
        out.append('"');

        // Fast path: scan for any char needing escaping
        int safe = 0;
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            boolean needsEscape = c < 128 ? NEEDS_ESCAPE[c] : false;
            if (needsEscape) {
                // Flush safe prefix in one shot
                if (i > safe) out.append(s, safe, i);
                switch (c) {
                    case '"'  -> out.append("\\\"");
                    case '\\' -> out.append("\\\\");
                    case '\n' -> out.append("\\n");
                    case '\r' -> out.append("\\r");
                    case '\t' -> out.append("\\t");
                    default   -> out.append("\\u00").append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
                }
                safe = i + 1;
            }
        }

        // Flush remaining safe tail (or entire string if no escaping needed)
        if (safe < len) out.append(s, safe, len);
        out.append('"');
    }

    private static final char[] HEX = "0123456789abcdef".toCharArray();
}