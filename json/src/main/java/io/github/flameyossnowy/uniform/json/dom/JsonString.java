package io.github.flameyossnowy.uniform.json.dom;

import org.jetbrains.annotations.NotNull;

public record JsonString(String value) implements JsonValue {
    private static final String[] ESCAPE = new String[128];
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    static {
        ESCAPE['"']  = "\\\"";
        ESCAPE['\\'] = "\\\\";
        ESCAPE['\n'] = "\\n";
        ESCAPE['\r'] = "\\r";
        ESCAPE['\t'] = "\\t";
        ESCAPE['\b'] = "\\b";
        ESCAPE['\f'] = "\\f";
    }

    @Override
    public @NotNull String toString() {
        String s = value;

        if (!needsEscaping(s)) {
            return "\"" + s + "\"";
        }

        final int len = s.length();
        char[] buf = new char[len * 2 + 2];
        int pos = 0;

        buf[pos++] = '"';

        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);

            if (c >= 0x20 && c != '"' && c != '\\') {
                buf[pos++] = c;
                continue;
            }

            if (c < 128 && ESCAPE[c] != null) {
                String esc = ESCAPE[c];
                buf[pos++] = esc.charAt(0);
                buf[pos++] = esc.charAt(1);
            } else {
                buf[pos++] = '\\';
                buf[pos++] = 'u';
                buf[pos++] = HEX[(c >>> 12) & 0xF];
                buf[pos++] = HEX[(c >>> 8) & 0xF];
                buf[pos++] = HEX[(c >>> 4) & 0xF];
                buf[pos++] = HEX[c & 0xF];
            }
        }

        buf[pos++] = '"';

        return new String(buf, 0, pos);
    }

    private static boolean needsEscaping(String s) {
        for (int i = 0, len = s.length(); i < len; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == '"' || c == '\\') {
                return true;
            }
        }
        return false;
    }
}