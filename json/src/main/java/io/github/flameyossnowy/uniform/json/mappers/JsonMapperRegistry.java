package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.ByteSlice;
import io.github.flameyossnowy.uniform.json.reflect.ReflectionMapperFactory;

import java.util.BitSet;
import java.util.Collection;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.concurrent.ConcurrentHashMap;

public final class JsonMapperRegistry {
    private static final Map<Class<?>, JsonMapper<?>>       READERS = new ConcurrentHashMap<>(64);
    private static final Map<Class<?>, JsonWriterMapper<?>> WRITERS = new ConcurrentHashMap<>(64);

    private static volatile boolean    bootstrapped   = false;
    private static volatile JsonConfig activeConfig   = null;

    private JsonMapperRegistry() {}

    public static JsonConfig getActiveConfig() {
        return activeConfig;
    }

    private static void bootstrapIfNeeded() {
        if (bootstrapped) return;
        // Caller must hold lock on JsonMapperRegistry.class
        JsonMapperRegistry registry = new JsonMapperRegistry();
        JsonConfig cfg = activeConfig; // may be null if bootstrapped without config

        boolean loadedAny = false;
        for (JsonMapperModule module : ServiceLoader.load(JsonMapperModule.class)) {
            if (cfg != null) {
                module.register(registry, cfg);
            } else {
                module.register(registry);
            }
            loadedAny = true;
        }

        if (!loadedAny) {
            String override = System.getProperty("uniform.generatedModule");
            if (override != null && !override.isBlank()) {
                tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), cfg);
            }
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", cfg);
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", cfg);
        }

        bootstrapped = true;
    }

    private static void propagateConfigToModules(JsonMapperRegistry registry, JsonConfig config) {
        for (JsonMapperModule module : ServiceLoader.load(JsonMapperModule.class)) {
            module.register(registry, config);
        }
        String override = System.getProperty("uniform.generatedModule");
        if (override != null && !override.isBlank()) {
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), config);
        }
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", config);
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", config);
    }

    private static void tryRegisterByName(JsonMapperRegistry registry, String fqcn, JsonConfig config) {
        try {
            Class<?> clazz = Class.forName(fqcn);
            if (!JsonMapperModule.class.isAssignableFrom(clazz)) return;
            JsonMapperModule module = (JsonMapperModule) clazz.getDeclaredConstructor().newInstance();
            if (config != null) {
                module.register(registry, config);
            } else {
                module.register(registry);
            }
        } catch (Throwable ignored) {}
    }

    private static void tryRegisterByName(JsonMapperRegistry registry, String fqcn) {
        tryRegisterByName(registry, fqcn, activeConfig);
    }

    public <T> void registerReaderInstance(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    public <T> void registerWriterInstance(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        WRITERS.put(type, mapper);
    }

    public static <T> void registerReader(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    public static <T> void registerWriter(Class<T> type, JsonWriterMapper<? extends T> mapper) {
        WRITERS.put(type, mapper);
    }

    private static volatile ReflectionConfig reflectionConfig = ReflectionConfig.DEFAULT;

    public static void applyConfig(JsonConfig config) {
        if (config == null) return;
        synchronized (JsonMapperRegistry.class) {
            activeConfig      = config;
            reflectionConfig  = config.reflectionConfig();
            bootstrapIfNeeded();
            propagateConfigToModules(new JsonMapperRegistry(), config);
        }
    }

    public static JsonMapper<?> getReader(Class<?> type) {
        bootstrapIfNeededUnsafe();

        JsonMapper<?> mapper = READERS.get(type);
        if (mapper != null) return mapper;

        tryLoadMissingGeneratedModules();

        mapper = READERS.get(type);
        if (mapper != null) return mapper;

        if (Collection.class.isAssignableFrom(type)) {
            return COLLECTION_READER;
        }
        if (Map.class.isAssignableFrom(type)) {
            return MAP_READER;
        }

        if (reflectionConfig.enabled()) {
            return ReflectionMapperFactory.buildReader(type);
        }

        return null;
    }

    // Writer for BitSet (not a Collection, needs special handling)
    private static final JsonWriterMapper<BitSet> BITSET_WRITER = (out, value) -> {
        out.beginArray();
        for (int i = value.nextSetBit(0); i >= 0; i = value.nextSetBit(i + 1)) {
            out.value(i);
        }
        out.endArray();
    };

    // Dynamic writers for Collection and Map types to avoid JPMS issues with internal implementations
    private static final JsonWriterMapper<Collection<?>> COLLECTION_WRITER = (out, value) -> {
        out.beginArray();
        for (Object elem : value) {
            if (elem == null) {
                out.nullValue();
            } else {
                writeElement(out, elem);
            }
        }
        out.endArray();
    };

    private static final JsonWriterMapper<Map<?, ?>> MAP_WRITER = (out, value) -> {
        out.beginObject();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            out.name(String.valueOf(entry.getKey()));
            Object v = entry.getValue();
            if (v == null) out.nullValue();
            else writeElement(out, v);
        }
        out.endObject();
    };

    private static final JsonMapper<Collection<?>> COLLECTION_READER = (cursor) -> {
        JsonReadCursor arr = cursor;

        if (!arr.enterArray()) {
            return null;
        }

        java.util.List<Object> list = new java.util.ArrayList<>();

        while (arr.nextElement()) {
            Object value = readAny(arr.elementValueCursor());
            list.add(value);
        }

        return list;
    };

    private static final JsonMapper<Map<?, ?>> MAP_READER = (cursor) -> {
        JsonReadCursor obj = cursor;

        if (!obj.enterObject()) {
            return null;
        }

        Map<String, Object> map = new java.util.HashMap<>();

        while (obj.nextField()) {
            String key = obj.fieldNameAsString();
            Object value = readAny(obj.fieldValueCursor());
            map.put(key, value);
        }

        return map;
    };

    private static Object readAny(JsonReadCursor cursor) {
        // Peek at first non-whitespace char to determine type
        // Both elementValue() and fieldValue() now work on sub-cursors
        ByteSlice raw = cursor.elementValue();
        if (raw.length() == 0) {
            raw = cursor.fieldValue();
        }

        if (raw.length() == 0) {
            return null;
        }

        byte first = raw.byteAt(0);

        // Object: starts with {
        if (first == '{') {
            if (cursor.enterObject()) {
                Map<String, Object> map = new java.util.HashMap<>();
                while (cursor.nextField()) {
                    String key = cursor.fieldNameAsString();
                    Object value = readAny(cursor.fieldValueCursor());
                    map.put(key, value);
                }
                return map;
            }
            return null;
        }

        // Array: starts with [
        if (first == '[') {
            if (cursor.enterArray()) {
                java.util.List<Object> list = new java.util.ArrayList<>();
                while (cursor.nextElement()) {
                    Object value = readAny(cursor.elementValueCursor());
                    list.add(value);
                }
                return list;
            }
            return null;
        }

        // String: starts with "
        if (first == '"') {
            return cursor.elementValueAsUnquotedString();
        }

        // Null
        if (raw.length() == 4 &&
            raw.byteAt(0) == 'n' && raw.byteAt(1) == 'u' &&
            raw.byteAt(2) == 'l' && raw.byteAt(3) == 'l') {
            return null;
        }

        // Boolean: true/false
        if (raw.length() == 4 &&
            raw.byteAt(0) == 't' && raw.byteAt(1) == 'r' &&
            raw.byteAt(2) == 'u' && raw.byteAt(3) == 'e') {
            return Boolean.TRUE;
        }
        if (raw.length() == 5 &&
            raw.byteAt(0) == 'f' && raw.byteAt(1) == 'a' &&
            raw.byteAt(2) == 'l' && raw.byteAt(3) == 's' && raw.byteAt(4) == 'e') {
            return Boolean.FALSE;
        }

        // Number: parse as long if no decimal point, else double
        boolean hasDecimal = false;
        for (int i = 0; i < raw.length(); i++) {
            byte b = raw.byteAt(i);
            if (b == '.' || b == 'e' || b == 'E') {
                hasDecimal = true;
                break;
            }
        }

        String numStr = raw.toString();
        try {
            if (hasDecimal) {
                return Double.parseDouble(numStr);
            } else {
                long val = Long.parseLong(numStr);
                // Return as Integer if it fits
                if (val >= Integer.MIN_VALUE && val <= Integer.MAX_VALUE) {
                    return (int) val;
                }
                return val;
            }
        } catch (NumberFormatException e) {
            return numStr;
        }
    }

    private static void writeElement(io.github.flameyossnowy.uniform.json.writers.JsonStringWriter out, Object elem) {
        switch (elem) {
            case String s -> out.value(s);
            case Double d -> out.value(d);
            case Float f -> out.value(f);
            case Number n -> out.value(n.longValue());
            case Character n -> out.write(n);
            case Boolean b -> out.value(b);
            case Collection<?> col -> COLLECTION_WRITER.writeTo(out, col);
            case Map<?, ?> map -> MAP_WRITER.writeTo(out, map);
            default -> {
                // For other types, look up a writer and use it
                JsonWriterMapper<Object> writer = (JsonWriterMapper<Object>) getWriter(elem.getClass());
                if (writer != null) {
                    writer.writeTo(out, elem);
                } else {
                    out.nullValue();
                }
            }
        }
    }

    public static JsonWriterMapper<?> getWriter(Class<?> type) {
        bootstrapIfNeededUnsafe();
        JsonWriterMapper<?> mapper = WRITERS.get(type);
        if (mapper != null) return mapper;
        tryLoadMissingGeneratedModules();
        mapper = WRITERS.get(type);
        if (mapper != null) return mapper;

        // Check for specific types before Collection/Map to ensure proper handling
        if (BitSet.class.isAssignableFrom(type)) {
            return BITSET_WRITER;
        }
        // Check for Collection/Map before falling back to reflection
        // This avoids JPMS issues with internal immutable collection implementations
        // Note: Queue and Deque are subclasses of Collection, so they're handled here
        if (Collection.class.isAssignableFrom(type)) {
            return COLLECTION_WRITER;
        }
        if (Map.class.isAssignableFrom(type)) {
            return MAP_WRITER;
        }

        if (reflectionConfig.enabled()) {
            return ReflectionMapperFactory.buildWriter(type);
        }
        return null;
    }

    /** Fast unsynchronized check for the read/write hot path. */
    private static void bootstrapIfNeededUnsafe() {
        if (bootstrapped) return;
        synchronized (JsonMapperRegistry.class) {
            bootstrapIfNeeded();
        }
    }

    private static void tryLoadMissingGeneratedModules() {
        JsonMapperRegistry registry = new JsonMapperRegistry();
        JsonConfig cfg = activeConfig;
        String override = System.getProperty("uniform.generatedModule");
        if (override != null && !override.isBlank()) {
            tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated." + override.trim(), cfg);
        }
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModule", cfg);
        tryRegisterByName(registry, "io.github.flameyossnowy.uniform.generated.UniformGeneratedJsonModuleJmh", cfg);
    }
}