package me.flame.uniform.json.parser;

import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.parser.lowlevel.JsonCursor;
import me.flame.uniform.json.parser.lowlevel.MapJsonCursor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;

import java.util.Map;

public final class JsonCursors {
    private static final VectorByteScanner SCANNER = new VectorByteScanner();

    private JsonCursors() {
    }

    @Contract("_, _ -> new")
    public static @NotNull JsonCursor createNormal(byte @NotNull [] bytes, JsonConfig config) {
        ScanResult scan = ScanResult.create(bytes.length);
        SCANNER.scan(bytes, 0, bytes.length, scan);
        return new JsonCursor(bytes, scan, config);
    }

    @Contract("_ -> new")
    public static @NotNull MapJsonCursor createMap(Map<String, Object> map) {
        return new MapJsonCursor(map);
    }
}
