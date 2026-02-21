package me.flame.uniform.json.tokenizer;

public record Token(
    Type type,
    int start,
    int end,
    int depth
) {
    public enum Type {
        OBJECT_START,
        OBJECT_END,
        ARRAY_START,
        ARRAY_END,
        COLON,
        COMMA,
        STRING,
        NUMBER,
        TRUE,
        FALSE,
        NULL
    }

    public static Token of(Type type, int start, int end, int depth) {
        return new Token(type, start, end, depth);
    }
}
