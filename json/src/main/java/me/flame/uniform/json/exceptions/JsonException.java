package me.flame.uniform.json.exceptions;

import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public final class JsonException extends RuntimeException {
    public JsonException(String msg) { super(msg); }

    public JsonException(String msg, IOException e) { super(msg, e); }

    @NotNull
    @Contract("-> new")
    public static JsonException invalidUtf8() { return new JsonException("Invalid UTF-8"); }

    @NotNull
    @Contract("_ -> new")
    public static JsonException io(IOException e) { return new JsonException("An I/O exception has occured: " + e.getMessage(), e); }

    @NotNull
    @Contract("-> new")
    public static JsonException unterminatedString() { return new JsonException("Unterminated string"); }

    @NotNull
    @Contract("-> new")
    public static JsonException unescapedControl() { return new JsonException("Unescaped control character"); }

    @NotNull
    @Contract("-> new")
    public static JsonException truncated() {
        return new JsonException("The read bytes is lower than expected.");
    }

    @NotNull
    @Contract("_ -> new")
    public static JsonException unbalancedBrackets(int pos) {
        return new JsonException("Unbalanced brackets at " + pos);
    }

    public static JsonException invalidNumber(int pos) {
        return new JsonException("Invalid number at " + pos);
    }

    public static JsonException unexpectedEnd(int pos) {
        return new JsonException("Unexpected end of input at " + pos);
    }
}
