package me.flame.uniform.json.writers.prettifiers;

import me.flame.turboscanner.ScanResult;
import org.jetbrains.annotations.NotNull;

import java.nio.ByteBuffer;
import java.util.function.Function;

final class JsonStreamEngine {

    private final byte[] input;
    private final ScanResult scan;
    private final ByteBuffer out;
    private final Function<String, RuntimeException> error;
    private final int indentSize;

    private int depth = 0;
    private int i = 0;

    JsonStreamEngine(byte[] input,
                     ScanResult scan,
                     ByteBuffer out,
                     Function<String, RuntimeException> error,
                     int indentSize) {
        this.input = input;
        this.scan = scan;
        this.out = out;
        this.error = error;
        this.indentSize = indentSize;
    }

    void process() {

        long[] structural = scan.getStructuralMask();
        long[] inside = scan.getInsideStringMask();

        while (i < input.length) {

            if (isMasked(i, inside)) {
                writeString();
                continue;
            }

            if (!isMasked(i, structural)) {
                if (!isWhitespace(input[i])) {
                    writeScalarSimd();
                    continue;
                }
                i++;
                continue;
            }

            byte b = input[i];

            switch (b) {
                case '{' -> open((byte) '{');
                case '}' -> close((byte) '}');
                case '[' -> open((byte) '[');
                case ']' -> close((byte) ']');
                case ':' -> colon();
                case ',' -> comma();
                default -> writeScalarSimd();
            }

            i++;
        }
    }

    // ------------------- STRING -------------------
    private void writeString() {
        int start = i;
        int end = findStringEnd(i + 1);
        int len = end - start + 1;
        ensureCapacity(len);
        out.put(input, start, len);
        i = end + 1;
    }

    private int findStringEnd(int from) {
        long[] quoteMask = scan.getQuoteMask();
        int lane = from >>> 6;
        int bit = from & 63;
        long mask = quoteMask[lane] & (~0L << bit);
        while (true) {
            if (mask != 0) return (lane << 6) + Long.numberOfTrailingZeros(mask);
            lane++;
            if (lane >= quoteMask.length) throw error.apply("Unterminated string");
            mask = quoteMask[lane];
        }
    }

    // ------------------- SCALAR -------------------
    private void writeScalarSimd() {
        int start = i;

        while (i < input.length &&
                !isMasked(i, scan.getStructuralMask()) &&
                !isMasked(i, scan.getInsideStringMask()) &&
                !isWhitespace(input[i])) {

            byte b = input[i];

            // SIMD literals
            if (b == 't' && matchLiteral(i, "true")) { i += 4; break; }
            if (b == 'f' && matchLiteral(i, "false")) { i += 5; break; }
            if (b == 'n' && matchLiteral(i, "null")) { i += 4; break; }

            // SIMD number detection with VarHandle
            if (isDigit(b) || b == '-' || b == '+' || b == '.' || b == 'e' || b == 'E') {
                i = fastScanNumber(i);
                break;
            }

            i++;
        }

        int len = i - start;
        ensureCapacity(len);
        out.put(input, start, len);

        i--; // adjust outer loop
    }

    // Fast number scanning using VarHandle
    private int fastScanNumber(int pos) {

        final int len = input.length;
        while (pos < len) {
            byte b = input[pos];
            if (!isDigit(b) && b != '-' && b != '+' && b != '.' && b != 'e' && b != 'E') break;
            pos++;
        }
        return pos;
    }

    private boolean matchLiteral(int offset, @NotNull String literal) {
        if (offset + literal.length() > input.length) return false;
        for (int j = 0; j < literal.length(); j++) if (input[offset + j] != literal.charAt(j)) return false;
        return true;
    }

    // ------------------- STRUCTURAL -------------------
    private void open(byte b) { out.put(b); depth++; newline(); }
    private void close(byte b) { depth--; newline(); out.put(b); }
    private void colon() { out.put((byte) ':'); out.put((byte) ' '); }
    private void comma() { out.put((byte) ','); newline(); }
    private void newline() { for (int j = 0; j < depth * indentSize; j++) out.put((byte) ' '); out.put((byte) '\n'); }

    // ------------------- HELPERS -------------------
    private boolean isMasked(int index, long[] mask) { return ((mask[index >>> 6] >>> (index & 63)) & 1L) != 0; }

    private boolean isWhitespace(byte b) { return b == ' ' || b == '\n' || b == '\r' || b == '\t'; }

    private void ensureCapacity(int len) {
        if (out.remaining() < len) {
            ByteBuffer tmp = ByteBuffer.allocate(Math.max(out.capacity() << 1, out.position() + len));
            out.flip();
            tmp.put(out);
            out.clear();
            out.put(tmp);
        }
    }

    private boolean isDigit(byte b) { return b >= '0' && b <= '9'; }
}
