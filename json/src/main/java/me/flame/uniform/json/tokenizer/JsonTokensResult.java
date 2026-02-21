package me.flame.uniform.json.tokenizer;

import java.util.List;

public record JsonTokensResult(List<Token> tokens, List<Throwable> exceptions) {
    public static JsonTokensResult createTokens(List<Token> tokens) {
        return new JsonTokensResult(tokens, List.of());
    }

    public static JsonTokensResult completeExceptionally(List<Throwable> exceptions) {
        return new JsonTokensResult(List.of(), exceptions);
    }
}
