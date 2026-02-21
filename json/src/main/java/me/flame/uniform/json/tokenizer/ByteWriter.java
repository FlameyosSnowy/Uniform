package me.flame.uniform.json.tokenizer;

import java.nio.ByteBuffer;
import java.util.Arrays;

public final class ByteWriter {

    private byte[] buffer;
    private int position;

    public ByteWriter(int capacity) {
        this.buffer = new byte[Math.max(64, capacity)];
    }

    public void write(byte b) {
        ensureCapacity(1);
        buffer[position++] = b;
    }

    public void write(byte[] src) {
        write(src, 0, src.length);
    }

    public void write(byte[] src, int off, int len) {
        ensureCapacity(len);
        System.arraycopy(src, off, buffer, position, len);
        position += len;
    }

    private void ensureCapacity(int needed) {
        int required = position + needed;
        if (required > buffer.length) {
            int newCap = Math.max(buffer.length << 1, required);
            buffer = Arrays.copyOf(buffer, newCap);
        }
    }

    public ByteBuffer toByteBuffer() {
        return ByteBuffer.wrap(buffer, 0, position);
    }

    public int size() {
        return position;
    }
}
