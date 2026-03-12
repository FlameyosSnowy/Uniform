package io.github.flameyossnowy.uniform.json.parser;

import io.github.flameyossnowy.turboscanner.ScanResult;
import io.github.flameyossnowy.turboscanner.VectorByteScanner;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.JsonCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.MapJsonCursor;
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
