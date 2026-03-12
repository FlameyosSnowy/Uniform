package io.github.flameyossnowy.uniform.json.writers.prettifiers;

import me.flame.turboscanner.ScanResult;

import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.BiConsumer;

final class JsonStreamEngine {
    // Whitespace lookup (256 B, fits in two cache lines)
    // true = NOT whitespace (i.e., the byte is significant)
    private static final boolean[] NOT_WS = new boolean[256];
    static {
        for (int c = 0; c < 256; c++) {
            NOT_WS[c] = (c != ' ' && c != '\n' && c != '\r' && c != '\t');
        }
    }

    // Indent table: MAX_DEPTH * indentSize bytes pre-filled with spaces
    // We allow up to 64 levels of nesting; at 4 spaces each that's 256 B.
    // With the 256-B NOT_WS table total static memory = 512 B exactly.
    private static final int MAX_DEPTH = 64;

    private byte[] out;
    private int    pos;          // write cursor

    private final byte[]      input;
    private final long[]      insideStringMask;
    private final long[]      quoteMask;
    private final long[]      structuralMask;

    private final int  indentSize;
    private final byte[] indentTable; // depth*indentSize spaces, pre-built
    private int  depth = 0;
    private int  i     = 0;

    private boolean fatalError = false;

    // Error builder callback - kept as a field so JsonFormatter can supply its
    // position-aware factory without us coupling to it directly.
    private final BiConsumer<String, Integer> onError;

    JsonStreamEngine(byte[] input, ScanResult scan, int initialCapacity,
                     BiConsumer<String, Integer> onError,
                     int indentSize) {
        this.input            = input;
        this.insideStringMask = scan.getInsideStringMask();
        this.quoteMask        = scan.getQuoteMask();
        this.structuralMask   = scan.getStructuralMask();
        this.onError          = onError;
        this.indentSize       = indentSize;

        // Pre-build indent table
        int tableLen = MAX_DEPTH * indentSize;
        this.indentTable = new byte[tableLen];
        Arrays.fill(this.indentTable, (byte) ' ');

        // Output buffer - use power-of-two ceiling for cheaper grow math
        this.out = new byte[Math.max(Integer.highestOneBit(initialCapacity - 1) << 1, 64)];
    }

    void process() {
        final byte[]  inp  = input;
        final int     len  = inp.length;
        final long[]  ism  = insideStringMask;

        while (i < len && !fatalError) {
            final byte b = inp[i];

            if (b == '"') {
                writeString();
                continue;
            }

            // 2. Structural chars always handled regardless of string mask
            // (avoids a branch for the common case; switch is O(1) via table)
            switch (b) {
                case '{' -> {
                    open((byte) '{');
                    i++;
                    continue;
                }
                case '}' -> {
                    close((byte) '}');
                    i++;
                    continue;
                }
                case '[' -> {
                    open((byte) '[');
                    i++;
                    continue;
                }
                case ']' -> {
                    close((byte) ']');
                    i++;
                    continue;
                }
                case ':' -> {
                    colon();
                    i++;
                    continue;
                }
                case ',' -> {
                    comma();
                    i++;
                    continue;
                }
            }

            // 3. Inside-string guard for non-structural, non-quote bytes
            {
                final int  lane = i >>> 6;
                final long bit  = 1L << (i & 63);
                if (ism != null && lane < ism.length && (ism[lane] & bit) != 0) {
                    i++;
                    continue;
                }
            }

            // 4. Scalar value or whitespace
            if (NOT_WS[b & 0xFF]) {
                writeScalar();
            } else {
                i++;
            }
        }

        if (!fatalError && depth != 0) {
            onError.accept("Unclosed brackets/braces: depth=" + depth + " at end of input", len);
        }
    }

    private void writeString() {
        final int start = i;
        final int end   = findStringEnd(i + 1);
        if (end < 0) { fatalError = true; return; }
        final int len = end - start + 1;
        ensureN(len);
        System.arraycopy(input, start, out, pos, len);
        pos += len;
        i    = end + 1;
    }

    /**
     * Finds the closing quote using the SIMD quote-position bitmask.
     * Falls back to a manual scan only when the mask doesn't cover the range.
     */
    private int findStringEnd(final int from) {
        final long[] qm = quoteMask;
        if (qm != null && qm.length > 0) {
            int  lane = from >>> 6;
            int  bit  = from & 63;
            if (lane < qm.length) {
                long mask = qm[lane] & (~0L << bit);   // zero bits before `from`
                while (true) {
                    if (mask != 0L) return (lane << 6) + Long.numberOfTrailingZeros(mask);
                    if (++lane >= qm.length) break;
                    mask = qm[lane];
                }
            }
        }
        return findStringEndManual(from);
    }

    private int findStringEndManual(final int from) {
        final byte[] inp = input;
        final int    len = inp.length;
        boolean escaped  = false;
        for (int j = from; j < len; j++) {
            final byte b = inp[j];
            if (escaped)   { escaped = false; continue; }
            if (b == '\\') { escaped = true;  continue; }
            if (b == '"')  { return j; }
        }
        onError.accept("Unterminated string starting at offset " + (from - 1), from - 1);
        return -1;
    }

    private void writeScalar() {
        final int     start = i;
        final byte[]  inp   = input;
        final int     len   = inp.length;
        final long[]  sm    = structuralMask;

        outer:
        while (i < len) {
            final byte b = inp[i];

            // Stop at whitespace or structural byte
            if (!NOT_WS[b & 0xFF]) break;
            {
                final int lane = i >>> 6;
                if (sm != null && lane < sm.length && ((sm[lane] >>> (i & 63)) & 1L) != 0) break;
            }

            if (b == '"') break; // hand off to writeString next iteration

            switch (b) {
                case 't' -> {
                    if (matchLiteral(i, "true"))  { i += 4; break outer; }
                    onError.accept("Invalid literal at offset " + i, i); i++; break outer;
                }
                case 'f' -> {
                    if (matchLiteral(i, "false")) { i += 5; break outer; }
                    onError.accept("Invalid literal at offset " + i, i); i++; break outer;
                }
                case 'n' -> {
                    if (matchLiteral(i, "null"))  { i += 4; break outer; }
                    onError.accept("Invalid literal at offset " + i, i); i++; break outer;
                }
                default -> {
                    // Number characters (digits, sign, decimal, exponent)
                    if (b >= '0' && b <= '9' || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E') {
                        i = fastScanNumber(i);
                        break outer;
                    }
                    onError.accept("Unexpected byte '" + (char) b + "' at offset " + i, i);
                    i++;
                    break outer;
                }
            }
        }

        final int copyLen = i - start;
        if (copyLen == 0) { i++; return; } // safety: guarantee forward progress
        ensureN(copyLen);
        System.arraycopy(inp, start, out, pos, copyLen);
        pos += copyLen;
    }

    private int fastScanNumber(int p) {
        final byte[] inp = input;
        final int    len = inp.length;
        while (p < len) {
            final byte b = inp[p];
            if (b < '0' && b != '-' && b != '+' && b != '.') break;
            if (b > '9' && b != 'e' && b != 'E') break;
            p++;
        }
        return p;
    }

    private boolean matchLiteral(final int offset, @NotNull final String literal) {
        final int litLen = literal.length();
        if (offset + litLen > input.length) return false;
        for (int j = 0; j < litLen; j++) {
            if (input[offset + j] != (byte) literal.charAt(j)) return false;
        }
        return true;
    }

    private void open(final byte b) {
        ensureN(1 + 1 + (depth + 1) * indentSize); // b + '\n' + indent
        out[pos++] = b;
        depth++;
        writeNewline();
    }

    private void close(final byte b) {
        if (depth <= 0) {
            onError.accept("Unexpected closing '" + (char) b
                + "' at offset " + i + " (depth already 0)", i);
            return;
        }
        depth--;
        // Overwrite the trailing indent that was written after the last comma/open
        // with the closing bracket at the correct indentation level.
        ensureN(1 + depth * indentSize + 1); // '\n' + indent + b
        writeNewline();
        out[pos++] = b;
    }

    private void colon() {
        ensureN(2);
        out[pos++] = ':';
        out[pos++] = ' ';
    }

    private void comma() {
        ensureN(1 + 1 + depth * indentSize); // ',' + '\n' + indent
        out[pos++] = ',';
        writeNewline();
    }

    /** Writes '\n' followed by depth*indentSize spaces using a bulk arraycopy. */
    private void writeNewline() {
        out[pos++] = '\n';
        final int spaces = depth * indentSize;
        if (spaces > 0) {
            System.arraycopy(indentTable, 0, out, pos, spaces);
            pos += spaces;
        }
    }

    /** Ensures at least {@code n} bytes remain in {@code out}. */
    private void ensureN(final int n) {
        if (pos + n <= out.length) return;
        // Double until large enough - bit-twiddling avoids division
        int newCap = out.length;
        do { newCap <<= 1; } while (pos + n > newCap);
        out = Arrays.copyOf(out, newCap);
    }

    public ByteBuffer getOutput() {
        int end = pos;
        while (end > 0) {
            final byte b = out[end - 1];
            if (b == ' ' || b == '\n' || b == '\r' || b == '\t') { end--; }
            else break;
        }
        return ByteBuffer.wrap(out, 0, end);
    }
}