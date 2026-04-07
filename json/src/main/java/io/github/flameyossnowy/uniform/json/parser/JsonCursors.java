package io.github.flameyossnowy.uniform.json.parser;

import io.github.flameyossnowy.turboscanner.ScanResult;
import io.github.flameyossnowy.turboscanner.VectorByteScanner;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.dom.JsonObject;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.JsonCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.JsonCursorCache;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.JsonObjectCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.MapJsonCursor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class JsonCursors {
    private static final VectorByteScanner SCANNER = new VectorByteScanner();

    private JsonCursors() {}

    /**
     * For large inputs (>= SIMD_THRESHOLD): pre-scan with SIMD bitmasks,
     * then walk bitmasks during parsing. Amortized win for many fields.
     */
    @Contract("_, _ -> new")
    public static @NotNull JsonCursor createNormal(byte @NotNull [] bytes, JsonConfig config) {
        JsonCursorCache cache = JsonCursorCache.get();

        if (bytes.length < JsonCursorCache.SIMD_THRESHOLD) {
            // Small input: skip pre-scan entirely
            byte[] decodeBuf = cache.acquireDecodeBuffer(Math.max(64, bytes.length));
            return new JsonCursor(true, cache, bytes, decodeBuf, null, config);
        }

        // Large input: SIMD pre-scan pays off
        ScanResult scan = cache.acquireScanResult(bytes.length);
        SCANNER.scan(bytes, 0, bytes.length, scan);
        byte[] decodeBuf = cache.acquireDecodeBuffer(Math.max(64, bytes.length / 4));
        return new JsonCursor(false, cache, bytes, decodeBuf, scan, config);
    }


    public static @NotNull MapJsonCursor createMap(Map<String, Object> map) {
        return new MapJsonCursor(map);
    }

    public static @NotNull JsonObjectCursor createJsonValueMap(JsonValue value) {
        if (value instanceof JsonObject obj) {
            return new JsonObjectCursor(obj);
        } else {
            throw new IllegalArgumentException("Cannot put " + value.getClass() + " into a JsonObjectCursor.");
        }
    }
}