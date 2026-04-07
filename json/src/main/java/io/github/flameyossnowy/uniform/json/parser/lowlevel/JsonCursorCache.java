package io.github.flameyossnowy.uniform.json.parser.lowlevel;

import io.github.flameyossnowy.turboscanner.ScanResult;

/**
 * Thread-local cache for JsonCursor parsing infrastructure.
 * Eliminates per-parse allocation of ScanResult long[] arrays
 * and the decode scratch buffer.
 */
public final class JsonCursorCache {

    // Below this threshold, skip SIMD pre-scan entirely
    // Tuned empirically: at ~4KB the pre-scan cost breaks even with bitmask-walk savings.
    public static final int SIMD_THRESHOLD = 4096;

    private static final ThreadLocal<JsonCursorCache> CACHE =
        ThreadLocal.withInitial(JsonCursorCache::new);

    private ScanResult scanResult;
    private int        scanResultCapacity;

    private byte[] decodeBuffer = new byte[1024];

    private JsonCursorCache() {
        // Start with capacity for 1KB input; grows on demand
        scanResultCapacity = 1024;
        scanResult = ScanResult.create(scanResultCapacity);
    }

    public static JsonCursorCache get() {
        return CACHE.get();
    }

    /**
     * Returns a reset ScanResult sized for the given input length.
     * Grows the backing arrays if needed, resets all bitmasks to zero.
     */
    public ScanResult acquireScanResult(int inputLength) {
        if (inputLength > scanResultCapacity) {
            // Grow by 2x or to exact need, whichever is larger
            scanResultCapacity = Math.max(inputLength, scanResultCapacity * 2);
            scanResult = ScanResult.create(scanResultCapacity);
        } else {
            scanResult.clear(); // zero the bitmask arrays without reallocation
        }
        return scanResult;
    }

    public byte[] acquireDecodeBuffer(int minSize) {
        if (decodeBuffer.length < minSize) {
            decodeBuffer = new byte[Math.max(minSize, decodeBuffer.length * 2)];
        }
        return decodeBuffer;
    }

    public void releaseDecodeBuffer(byte[] buf) {
        // If the buffer grew during parsing, keep the larger one for next time
        if (buf.length > decodeBuffer.length) {
            decodeBuffer = buf;
        }
    }
}