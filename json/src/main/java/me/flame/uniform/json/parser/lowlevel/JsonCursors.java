package me.flame.uniform.json.parser.lowlevel;

import me.flame.turboscanner.ScanResult;
import me.flame.turboscanner.VectorByteScanner;

public final class JsonCursors {
    private static final VectorByteScanner SCANNER = new VectorByteScanner();

    private JsonCursors() {
    }

    public static JsonCursor create(byte[] bytes) {
        ScanResult scan = ScanResult.create(bytes.length);
        SCANNER.scan(bytes, 0, bytes.length, scan);
        return new JsonCursor(bytes, scan);
    }
}
