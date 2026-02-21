package me.flame.uniform.json.parser.lowlevel;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.StandardCharsets;

public record ByteSlice(byte[] data, int offset, int length) {
    @Contract(value = " -> new", pure = true)
    @Override
    public @NotNull String toString() {
        return new String(data, offset, length, StandardCharsets.UTF_8);
    }
}
