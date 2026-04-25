package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.core.CollectionKind;
import io.github.flameyossnowy.uniform.core.resolvers.ContextDynamicTypeSupplier;
import io.github.flameyossnowy.uniform.core.resolvers.ResolutionContext;
import io.github.flameyossnowy.uniform.core.resolvers.ResolverRegistry;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import org.jetbrains.annotations.Contract;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * Reflection-based JSON reader delegate supporting full CollectionKind and arrays,
 * null handling, sparse arrays, primitive vs object arrays, and module-safe reflection.
 */
public final class ReflectionReadDelegate {

    private ReflectionReadDelegate() {}

    public static Object read(ReflectionMetadata meta, JsonReadCursor cursor) {
        List<ReflectionMetadata.Property> props = meta.properties;
        int count = props.size();

        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            Class<?> type = props.get(i).type();
            // Only pre-fill primitives
            values[i] = type.isPrimitive() ? defaultValue(type) : null;
        }

        Map<String, Integer> index = buildIndex(props);

        cursor.enterObject();
        while (cursor.nextField()) {
            String fieldName = cursor.fieldNameAsString();
            Integer idx = index.get(fieldName);
            if (idx == null) {
                cursor.skipFieldValue();
                continue;
            }
            ReflectionMetadata.Property prop = props.get(idx);
            values[idx] = readValue(cursor, prop.type(), prop.genericType(), prop, prop.owner());
        }

        return instantiate(meta, values);
    }

    private static Object readValue(JsonReadCursor cursor, Class<?> type, Type genericType,
                                    ReflectionMetadata.Property prop, Class<?> owner) {
        // Step 0: runtime supplier
        ContextDynamicTypeSupplier<?> supplier = ResolverRegistry.getSupplier(type);
        if (supplier != null) {
            ResolutionContext ctx = new ResolutionContext() {
                @Override public Class<?> declaredType() { return type; }
                @Override public Class<?> ownerType() { return owner; }
                @Override public String propertyName() { return prop != null ? prop.name() : null; }
                @Override public AnnotatedElement annotatedElement() { return prop != null ? prop.type() : null; }
            };
            Class<?> runtimeType = supplier.supply(ctx);
            if (runtimeType != null && runtimeType != type) {
                return readValue(cursor, runtimeType, runtimeType, prop, owner);
            }
        }

        // Step 1: primitives and common types
        if (type == String.class) return cursor.fieldValueAsUnquotedString();
        if (type == int.class || type == Integer.class) return cursor.fieldValueAsInt();
        if (type == long.class || type == Long.class) return cursor.fieldValueAsLong();
        if (type == double.class || type == Double.class) return cursor.fieldValueAsDouble();
        if (type == float.class || type == Float.class) return cursor.fieldValueAsFloat();
        if (type == boolean.class || type == Boolean.class) return cursor.fieldValueAsBoolean();
        if (type == short.class || type == Short.class) return cursor.fieldValueAsShort();
        if (type == byte.class || type == Byte.class) return cursor.fieldValueAsByte();

        // Step 2: collections / arrays
        CollectionKind kind = determineCollectionKind(type);
        switch (kind) {
            case LIST -> {
                Class<?> elementType = resolveFirstTypeArg(genericType);
                return readList(cursor, elementType, ArrayList::new);
            }
            case SET -> {
                Class<?> elementType = resolveFirstTypeArg(genericType);
                return readList(cursor, elementType, LinkedHashSet::new);
            }
            case QUEUE -> {
                Class<?> elementType = resolveFirstTypeArg(genericType);
                return readList(cursor, elementType, ArrayDeque::new);
            }
            case MAP -> {
                Type[] args = (genericType instanceof ParameterizedType pt) ? pt.getActualTypeArguments() : null;
                Class<?> keyType = (args != null && args.length > 0 && args[0] instanceof Class<?> c) ? c : Object.class;
                Class<?> valueType = (args != null && args.length > 1 && args[1] instanceof Class<?> c) ? c : Object.class;
                return readMap(cursor, keyType, valueType);
            }
            case ARRAY -> {
                return readArray(cursor, type.getComponentType());
            }
            case NONE -> {
                // fallback to nested object
                JsonMapper<?> nested = JsonMapperRegistry.getReader(type);
                if (nested == null) nested = ReflectionMapperFactory.buildReader(type);
                return nested.map(cursor.fieldValueCursor());
            }
        }
        throw new IllegalStateException("Unsupported type: " + type);
    }

    private static <C extends Collection<Object>> C readList(JsonReadCursor cursor, Class<?> elementType, Supplier<C> supplier) {
        C collection = supplier.get();
        JsonReadCursor sub = cursor.fieldValueCursor();
        if (!sub.enterArray()) return nullIfEmpty(collection);
        while (sub.nextElement()) {
            collection.add(sub.elementIsNull() ? null : readElement(sub, elementType));
        }
        return collection;
    }

    @Nullable
    private static Object readArray(@NotNull JsonReadCursor cursor, Class<?> elementType) {
        JsonReadCursor sub = cursor.fieldValueCursor();
        if (!sub.enterArray()) return null;

        List<Object> tmp = new ArrayList<>();
        while (sub.nextElement()) {
            tmp.add(sub.elementIsNull() ? null : readElement(sub, elementType));
        }

        Object array = Array.newInstance(elementType, tmp.size());
        for (int i = 0; i < tmp.size(); i++) Array.set(array, i, tmp.get(i));
        return array;
    }

    private static Map<Object, Object> readMap(JsonReadCursor cursor, Class<?> keyType, Class<?> valueType) {
        JsonReadCursor sub = cursor.fieldValueCursor();
        if (!sub.enterObject()) return null;

        Map<Object, Object> map = new LinkedHashMap<>();
        while (sub.nextField()) {
            Object key = convertMapKey(sub.fieldNameAsString(), keyType);
            Object value = readValue(sub, valueType, valueType, null, null);
            map.put(key, value);
        }
        return map;
    }

    private static Object convertMapKey(String key, Class<?> keyType) {
        if (keyType == String.class) return key;
        if (keyType == Integer.class || keyType == int.class) return Integer.valueOf(key);
        if (keyType == Long.class || keyType == long.class) return Long.valueOf(key);
        // Add other conversions if needed
        return key;
    }

    private static Object readElement(JsonReadCursor cursor, Class<?> type) {
        if (type == null || type == Object.class || type == String.class) return cursor.elementValueAsUnquotedString();
        if (type == int.class   || type == Integer.class)  return cursor.elementValueAsInt();
        if (type == long.class  || type == Long.class)     return cursor.elementValueAsLong();
        if (type == double.class|| type == Double.class)   return cursor.elementValueAsDouble();
        if (type == float.class || type == Float.class)    return cursor.elementValueAsFloat();
        if (type == boolean.class|| type == Boolean.class) return cursor.elementValueAsBoolean();

        JsonMapper<?> mapper = JsonMapperRegistry.getReader(type);
        if (mapper == null) mapper = ReflectionMapperFactory.buildReader(type);
        return mapper.map(cursor.elementValueCursor());
    }

    private static Object instantiate(ReflectionMetadata meta, Object[] values) {
        try {
            if (meta.isRecord || meta.canonicalConstructor != null) {
                return meta.canonicalConstructor.newInstance(values);
            }

            if (meta.noArgConstructor == null) throw new IllegalStateException(
                "No no-arg constructor for " + meta.type + " and it is not a record.");

            Object instance = meta.noArgConstructor.newInstance();
            List<ReflectionMetadata.Property> props = meta.properties;
            for (int i = 0; i < props.size(); i++) {
                ReflectionMetadata.Property prop = props.get(i);
                if (prop.writer() != null && values[i] != null) prop.write(instance, values[i]);
            }
            return instance;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate " + meta.type, e);
        }
    }

    private static Object defaultValue(Class<?> type) {
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        if (type == float.class) return 0.0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        return null;
    }

    private static Class<?> resolveFirstTypeArg(Type genericType) {
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> c) return c;
        }
        return Object.class;
    }

    private static Map<String, Integer> buildIndex(List<ReflectionMetadata.Property> props) {
        Map<String, Integer> map = new HashMap<>(props.size() * 2);
        for (int i = 0; i < props.size(); i++) map.put(props.get(i).name(), i);
        return map;
    }

    @Nullable
    @Contract(pure = true)
    private static <C extends Collection<?>> C nullIfEmpty(@NotNull C col) {
        return col.isEmpty() ? null : col;
    }

    private static CollectionKind determineCollectionKind(@NotNull Class<?> type) {
        if (type.isArray()) return CollectionKind.ARRAY;
        if (List.class.isAssignableFrom(type)) return CollectionKind.LIST;
        if (Set.class.isAssignableFrom(type)) return CollectionKind.SET;
        if (Queue.class.isAssignableFrom(type)) return CollectionKind.QUEUE;
        if (Map.class.isAssignableFrom(type)) return CollectionKind.MAP;
        return CollectionKind.NONE;
    }
}