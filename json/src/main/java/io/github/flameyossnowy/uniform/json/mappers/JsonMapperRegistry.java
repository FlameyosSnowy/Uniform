package io.github.flameyossnowy.uniform.json.mappers;

import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.dom.JsonString;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.ByteSlice;
import io.github.flameyossnowy.uniform.json.reflect.ReflectionMapperFactory;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolver;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolverRegistry;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Array;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Stack;
import java.util.TreeSet;
import java.util.Vector;
import java.util.HashSet;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.IntFunction;
import java.util.function.Supplier;
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

    public static Collection<Object> newCollection(Class<?> type) {
        Supplier<Collection<Object>> factory = collectionFactory(type);
        return factory == null ? null : factory.get();
    }

    public static Object readUntyped(JsonReadCursor cursor) {
        return readAny(cursor);
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

    @SuppressWarnings("MethodMayBeStatic")
    public <T> void registerReaderInstance(Class<T> type, JsonMapper<? extends T> mapper) {
        READERS.put(type, mapper);
    }

    @SuppressWarnings("MethodMayBeStatic")
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
            return collectionReaderFor(type);
        }

        if (Map.class.isAssignableFrom(type)) {
            return MAP_READER;
        }

        if (type == BitSet.class) {
            return BITSET_READER;
        }

        if (type.isArray()) {
            return arrayReaderFor(type);
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

    // Writer for array
    private static final JsonWriterMapper<Object> ARRAY_WRITER = (out, value) -> {
        out.beginArray();
        final int n = Array.getLength(value);
        for (int i = 0; i < n; i++) {
            Object elem = Array.get(value, i);
            if (elem == null) out.nullValue();
            else writeElement(out, elem);
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

    private static final JsonMapper<BitSet> BITSET_READER = cursor -> {
        if (!cursor.enterArray()) return null;
        BitSet bits = new BitSet();
        while (cursor.nextElement()) {
            bits.set(cursor.elementValueAsInt());
        }
        return bits;
    };

    private static final JsonWriterMapper<Map<?, ?>> MAP_WRITER = (out, value) -> {
        out.beginObject();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            out.name(writeMapKey(entry.getKey()));
            Object v = entry.getValue();
            if (v == null) out.nullValue();
            else writeElement(out, v);
        }
        out.endObject();
    };

    private static final Map<Class<?>, Supplier<Collection<Object>>> COLLECTION_FACTORIES = new ConcurrentHashMap<>(32);

    static {
        COLLECTION_FACTORIES.put(ArrayList.class, ArrayList::new);
        COLLECTION_FACTORIES.put(LinkedList.class, LinkedList::new);
        COLLECTION_FACTORIES.put(Vector.class, Vector::new);
        COLLECTION_FACTORIES.put(Stack.class, Stack::new);
        COLLECTION_FACTORIES.put(CopyOnWriteArrayList.class, CopyOnWriteArrayList::new);
        COLLECTION_FACTORIES.put(HashSet.class, HashSet::new);
        COLLECTION_FACTORIES.put(LinkedHashSet.class, LinkedHashSet::new);
        COLLECTION_FACTORIES.put(TreeSet.class, TreeSet::new);
        COLLECTION_FACTORIES.put(CopyOnWriteArraySet.class, CopyOnWriteArraySet::new);
        COLLECTION_FACTORIES.put(ConcurrentSkipListSet.class, ConcurrentSkipListSet::new);
        COLLECTION_FACTORIES.put(ArrayDeque.class, ArrayDeque::new);
        COLLECTION_FACTORIES.put(PriorityQueue.class, PriorityQueue::new);
        COLLECTION_FACTORIES.put(ConcurrentLinkedQueue.class, ConcurrentLinkedQueue::new);
        COLLECTION_FACTORIES.put(ConcurrentLinkedDeque.class, ConcurrentLinkedDeque::new);
        COLLECTION_FACTORIES.put(LinkedBlockingQueue.class, LinkedBlockingQueue::new);
        COLLECTION_FACTORIES.put(LinkedBlockingDeque.class, LinkedBlockingDeque::new);
    }

     public static <C extends Collection<?>> void registerCollectionFactory(Class<C> type, Supplier<? extends C> factory) {
        COLLECTION_FACTORIES.put(type, (Supplier<Collection<Object>>) factory);
        COLLECTION_READERS.remove(type);
    }

    private static final Map<Class<?>, JsonMapper<?>> ARRAY_READERS = new ConcurrentHashMap<>(16);

    private static JsonMapper<?> arrayReaderFor(Class<?> arrayType) {
        return ARRAY_READERS.computeIfAbsent(arrayType, t -> {
            final Class<?> component = t.getComponentType();
            return (JsonMapper<Object>) cursor -> {
                if (!cursor.enterArray()) return null;
                java.util.List<Object> list = new ArrayList<>(32);
                while (cursor.nextElement()) {
                    list.add(readAny(cursor.elementValueCursor()));
                }
                return toTypedArray(list, component);
            };
        });
    }

    private static Object toTypedArray(java.util.List<?> list, Class<?> component) {
        Object array = newArray(component, list.size());
        for (int i = 0; i < list.size(); i++) {
            Object v = list.get(i);
            if (component.isArray() && v instanceof java.util.List<?> nested) {
                v = toTypedArray(nested, component.getComponentType());
            } else if (v instanceof Number n) {
                v = coerceNumber(n, component);
            }
            if (v != null || !component.isPrimitive()) {
                Array.set(array, i, v);
            }
        }
        return array;
    }

    private static Object coerceNumber(Number n, Class<?> target) {
        if (target == int.class    || target == Integer.class) return n.intValue();
        if (target == long.class   || target == Long.class)    return n.longValue();
        if (target == short.class  || target == Short.class)   return n.shortValue();
        if (target == byte.class   || target == Byte.class)    return n.byteValue();
        if (target == float.class  || target == Float.class)   return n.floatValue();
        if (target == double.class || target == Double.class)  return n.doubleValue();
        return n;
    }

    private static final Map<Class<?>, IntFunction<Object>> ARRAY_FACTORIES = new ConcurrentHashMap<>(32);

    static {
        ARRAY_FACTORIES.put(String.class,     String[]::new);
        ARRAY_FACTORIES.put(Integer.class,    Integer[]::new);
        ARRAY_FACTORIES.put(Long.class,       Long[]::new);
        ARRAY_FACTORIES.put(Double.class,     Double[]::new);
        ARRAY_FACTORIES.put(Float.class,      Float[]::new);
        ARRAY_FACTORIES.put(Short.class,      Short[]::new);
        ARRAY_FACTORIES.put(Byte.class,       Byte[]::new);
        ARRAY_FACTORIES.put(Boolean.class,    Boolean[]::new);
        ARRAY_FACTORIES.put(Character.class,  Character[]::new);
        ARRAY_FACTORIES.put(Object.class,     Object[]::new);
        ARRAY_FACTORIES.put(int.class,        int[]::new);
        ARRAY_FACTORIES.put(long.class,       long[]::new);
        ARRAY_FACTORIES.put(double.class,     double[]::new);
        ARRAY_FACTORIES.put(float.class,      float[]::new);
        ARRAY_FACTORIES.put(short.class,      short[]::new);
        ARRAY_FACTORIES.put(byte.class,       byte[]::new);
        ARRAY_FACTORIES.put(boolean.class,    boolean[]::new);
        ARRAY_FACTORIES.put(char.class,       char[]::new);
        ARRAY_FACTORIES.put(String[].class,   String[][]::new);
        ARRAY_FACTORIES.put(int[].class,      int[][]::new);
    }

    @SuppressWarnings({"unchecked", "unused"})
    public static <T> void registerArrayFactory(Class<T> component, IntFunction<T[]> factory) {
        ARRAY_FACTORIES.put(component, (IntFunction<Object>) (IntFunction<?>) factory);
    }

    private static Object newArray(Class<?> component, int size) {
        IntFunction<Object> factory = ARRAY_FACTORIES.get(component);
        if (factory != null) return factory.apply(size);
        return Array.newInstance(component, size);
    }

    private static final Map<Class<?>, JsonMapper<?>> COLLECTION_READERS = new ConcurrentHashMap<>(16);

    private static @Nullable JsonMapper<?> collectionReaderFor(Class<?> type) {
        JsonMapper<?> cached = COLLECTION_READERS.get(type);
        if (cached != null) return cached;

        Supplier<Collection<Object>> factory = collectionFactory(type);
        if (factory == null) return null;

        JsonMapper<Collection<Object>> reader = cursor -> {
            if (!cursor.enterArray()) return null;
            Collection<Object> out = factory.get();
            while (cursor.nextElement()) {
                out.add(readAny(cursor.elementValueCursor()));
            }
            return out;
        };
        COLLECTION_READERS.put(type, reader);
        return reader;
    }

    private static Supplier<Collection<Object>> collectionFactory(Class<?> type) {
        Supplier<Collection<Object>> exact = COLLECTION_FACTORIES.get(type);
        if (exact != null) return exact;

        if (type.isAssignableFrom(ArrayList.class))           return ArrayList::new;
        if (type.isAssignableFrom(LinkedHashSet.class))       return LinkedHashSet::new;
        if (type.isAssignableFrom(TreeSet.class))             return TreeSet::new;
        if (type.isAssignableFrom(LinkedList.class))          return LinkedList::new;
        if (type.isAssignableFrom(LinkedBlockingDeque.class)) return LinkedBlockingDeque::new;
        return null;
    }

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
            case Enum<?> e -> out.value(e.name());
            case Object o when o.getClass().isArray() -> ARRAY_WRITER.writeTo(out, o);
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

        if (type.isArray()) {
            return ARRAY_WRITER;
        }

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

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Object readMapKey(String raw, Class<?> keyType) {
        if (keyType == String.class || keyType == Object.class || keyType == CharSequence.class) return raw;
        if (keyType == Integer.class || keyType == int.class)         return Integer.valueOf(raw);
        if (keyType == Long.class    || keyType == long.class)        return Long.valueOf(raw);
        if (keyType == Short.class   || keyType == short.class)       return Short.valueOf(raw);
        if (keyType == Byte.class    || keyType == byte.class)        return Byte.valueOf(raw);
        if (keyType == Double.class  || keyType == double.class)      return Double.valueOf(raw);
        if (keyType == Float.class   || keyType == float.class)       return Float.valueOf(raw);
        if (keyType == Boolean.class || keyType == boolean.class)     return Boolean.valueOf(raw);
        if (keyType == Character.class || keyType == char.class) {
            if (raw.length() != 1) throw new JsonException("Map key '" + raw + "' is not a single char");
            return raw.charAt(0);
        }
        if (keyType.isEnum()) {
            try {
                return Enum.valueOf((Class) keyType, raw);
            } catch (IllegalArgumentException e) {
                throw new JsonException("No enum constant " + raw + " for " + keyType.getCanonicalName());
            }
        }

        CoreTypeResolver<?> resolver = CoreTypeResolverRegistry.INSTANCE.resolve(keyType);
        if (resolver != null) return resolver.deserialize(new JsonString(raw));

        throw new JsonException("No CoreTypeResolver for map key type " + keyType.getName()
                + "; register one via CoreTypeResolverRegistry.INSTANCE.register(...)");
    }

    @SuppressWarnings("unchecked")
    public static String writeMapKey(Object key) {
        if (key == null) return "null";
        if (key instanceof String s) return s;
        if (key instanceof Number || key instanceof Boolean || key instanceof Character) return String.valueOf(key);
        if (key instanceof Enum<?> e) return e.name();

        CoreTypeResolver<Object> resolver = (CoreTypeResolver<Object>) CoreTypeResolverRegistry.INSTANCE.resolve(key.getClass());
        if (resolver != null) {
            String s = resolver.serialize(key).toString();
            // JsonString#toString may render the surrounding quotes
            if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
                return s.substring(1, s.length() - 1);
            }
            return s;
        }
        throw new JsonException("No CoreTypeResolver for map key type " + key.getClass().getName());
    }

    /** If the current field/element value is JSON null, consumes it and returns true. */
    public static boolean consumeIfNull(JsonReadCursor c, boolean element) {
        ByteSlice raw = element ? c.elementValue() : c.fieldValue();
        if (raw.length() == 4
                && raw.byteAt(0) == 'n' && raw.byteAt(1) == 'u'
                && raw.byteAt(2) == 'l' && raw.byteAt(3) == 'l') {
            if (element) c.elementValueCursor(); else c.fieldValueCursor(); // consumes the value
            return true;
        }
        return false;
    }
}