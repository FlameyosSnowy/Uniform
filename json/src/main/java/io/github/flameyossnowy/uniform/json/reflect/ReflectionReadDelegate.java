package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.core.resolvers.ContextDynamicTypeSupplier;
import io.github.flameyossnowy.uniform.core.resolvers.ResolutionContext;
import io.github.flameyossnowy.uniform.core.resolvers.ResolverRegistry;
import io.github.flameyossnowy.uniform.json.dom.JsonValue;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.parser.JsonReadCursor;
import io.github.flameyossnowy.uniform.json.parser.lowlevel.ByteSlice;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolver;
import io.github.flameyossnowy.uniform.json.resolvers.CoreTypeResolverRegistry;

import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class ReflectionReadDelegate {

    private ReflectionReadDelegate() {}

    /** Where the value we are about to read sits: an object field or an array element. */
    private enum Slot { FIELD, ELEMENT }

    public static Object read(ReflectionMetadata meta, JsonReadCursor cursor) {
        List<ReflectionMetadata.Property> props = meta.properties;
        int count = props.size();

        Object[] values = new Object[count];
        for (int i = 0; i < count; i++) {
            Class<?> type = props.get(i).type();
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
            Type generic = prop.genericType() != null ? prop.genericType() : prop.type();
            values[idx] = readValue(cursor, Slot.FIELD, generic, prop, prop.owner());
        }

        return instantiate(meta, values);
    }

    // ------------------------------------------------------------------
    // Core dispatch
    // ------------------------------------------------------------------

    private static Object readValue(JsonReadCursor cursor, Slot slot, Type genericType,
                                    ReflectionMetadata.Property prop, Class<?> owner) {
        Class<?> type = rawClass(genericType);

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
                return readValue(cursor, slot, runtimeType, prop, owner);
            }
        }

        if (isNullValue(cursor, slot)) {
            subCursor(cursor, slot); // consumes the value so the parent cursor moves past "null"
            return type.isPrimitive() ? defaultValue(type) : null;
        }

        // Untyped: Object / wildcard / unbound type variable
        if (type == Object.class) {
            return JsonMapperRegistry.readUntyped(subCursor(cursor, slot));
        }

        // Primitives, wrappers, String
        if (type == String.class || type == CharSequence.class) return slot == Slot.FIELD ? cursor.fieldValueAsUnquotedString() : cursor.elementValueAsUnquotedString();
        if (type == int.class     || type == Integer.class)   return slot == Slot.FIELD ? cursor.fieldValueAsInt()     : cursor.elementValueAsInt();
        if (type == long.class    || type == Long.class)      return slot == Slot.FIELD ? cursor.fieldValueAsLong()    : cursor.elementValueAsLong();
        if (type == double.class  || type == Double.class)    return slot == Slot.FIELD ? cursor.fieldValueAsDouble()  : cursor.elementValueAsDouble();
        if (type == float.class   || type == Float.class)     return slot == Slot.FIELD ? cursor.fieldValueAsFloat()   : cursor.elementValueAsFloat();
        if (type == boolean.class || type == Boolean.class)   return slot == Slot.FIELD ? cursor.fieldValueAsBoolean() : cursor.elementValueAsBoolean();
        if (type == short.class   || type == Short.class)     return slot == Slot.FIELD ? cursor.fieldValueAsShort()   : (short) cursor.elementValueAsInt();
        if (type == byte.class    || type == Byte.class)      return slot == Slot.FIELD ? cursor.fieldValueAsByte()    : (byte) cursor.elementValueAsInt();
        if (type == char.class    || type == Character.class) {
            String s = slot == Slot.FIELD ? cursor.fieldValueAsUnquotedString() : cursor.elementValueAsUnquotedString();
            return s.isEmpty() ? '\0' : s.charAt(0);
        }

        // BitSet
        if (type == BitSet.class) {
            JsonReadCursor sub = subCursor(cursor, slot);
            if (!sub.enterArray()) return null;
            BitSet bits = new BitSet();
            while (sub.nextElement()) bits.set(sub.elementValueAsInt());
            return bits;
        }

        // Arrays (primitive and object, nested)
        if (type.isArray()) {
            Type component = genericType instanceof GenericArrayType ga
                    ? ga.getGenericComponentType() : type.getComponentType();
            return readArray(cursor, slot, component, owner);
        }

        // Collections: List, Set, Queue, Deque, SortedSet, Collection, concrete types...
        if (Collection.class.isAssignableFrom(type)) {
            return readCollection(cursor, slot, type, typeArg(genericType, 0), owner);
        }

        // Maps
        if (Map.class.isAssignableFrom(type)) {
            return readMap(cursor, slot, type, typeArg(genericType, 0), typeArg(genericType, 1), owner);
        }

        // Core resolvers: enums, UUID, BigDecimal, java.time.*, custom CoreTypeResolvers...
        CoreTypeResolver<?> core = CoreTypeResolverRegistry.INSTANCE.resolve(type);
        if (core != null) {
            JsonValue jv = slot == Slot.FIELD ? cursor.fieldValueAsJsonValue() : cursor.elementValueAsJsonValue();
            try {
                return core.deserialize(jv);
            } catch (IllegalArgumentException e) {
                throw new JsonException("Cannot read " + type.getSimpleName() + " from " + jv
                        + (slot == Slot.FIELD ? " at field '" + cursor.fieldNameAsString() + "'" : " in array"), e);
            }
        }

        // Nested POJO / record: generated mapper first, reflection second
        JsonMapper<?> nested = JsonMapperRegistry.getReader(type);
        if (nested == null) nested = ReflectionMapperFactory.buildReader(type);
        return nested.map(subCursor(cursor, slot));
    }

    // ------------------------------------------------------------------
    // Containers
    // ------------------------------------------------------------------

    private static Object readCollection(JsonReadCursor cursor, Slot slot, Class<?> collectionType,
                                         Type elementType, Class<?> owner) {
        JsonReadCursor sub = subCursor(cursor, slot);
        if (!sub.enterArray()) return null;

        Collection<Object> out = JsonMapperRegistry.newCollection(collectionType);
        if (out == null) {
            throw new JsonException("Cannot create " + collectionType.getName()
                    + "; register it with JsonMapperRegistry.registerCollectionFactory");
        }
        while (sub.nextElement()) {
            out.add(readValue(sub, Slot.ELEMENT, elementType, null, owner));
        }
        return out;
    }

    private static Object readArray(JsonReadCursor cursor, Slot slot, Type componentType, Class<?> owner) {
        JsonReadCursor sub = subCursor(cursor, slot);
        if (!sub.enterArray()) return null;

        List<Object> tmp = new ArrayList<>();
        while (sub.nextElement()) {
            tmp.add(readValue(sub, Slot.ELEMENT, componentType, null, owner));
        }

        Object array = Array.newInstance(rawClass(componentType), tmp.size());
        for (int i = 0; i < tmp.size(); i++) Array.set(array, i, tmp.get(i));
        return array;
    }

    private static Object readMap(JsonReadCursor cursor, Slot slot, Class<?> mapType,
                                  Type keyType, Type valueType, Class<?> owner) {
        JsonReadCursor sub = subCursor(cursor, slot);
        if (!sub.enterObject()) return null;

        Class<?> keyClass = rawClass(keyType);
        Map<Object, Object> map = newMap(mapType);
        while (sub.nextField()) {
            Object key = convertMapKey(sub.fieldNameAsString(), keyClass);
            map.put(key, readValue(sub, Slot.FIELD, valueType, null, owner));
        }
        return map;
    }

    private static Map<Object, Object> newMap(Class<?> type) {
        if (type.isAssignableFrom(LinkedHashMap.class))   return new LinkedHashMap<>();   // Map, HashMap, LinkedHashMap
        if (type.isAssignableFrom(TreeMap.class))         return new TreeMap<>();         // SortedMap, NavigableMap, TreeMap
        if (type.isAssignableFrom(ConcurrentHashMap.class)) return new ConcurrentHashMap<>(); // ConcurrentMap
        throw new JsonException("Unsupported map type " + type.getName());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object convertMapKey(String key, Class<?> k) {
        if (k == String.class || k == Object.class || k == CharSequence.class) return key;
        if (k == int.class     || k == Integer.class)    return Integer.valueOf(key);
        if (k == long.class    || k == Long.class)       return Long.valueOf(key);
        if (k == short.class   || k == Short.class)      return Short.valueOf(key);
        if (k == byte.class    || k == Byte.class)       return Byte.valueOf(key);
        if (k == double.class  || k == Double.class)     return Double.valueOf(key);
        if (k == float.class   || k == Float.class)      return Float.valueOf(key);
        if (k == boolean.class || k == Boolean.class)    return Boolean.valueOf(key);
        if (k == char.class    || k == Character.class) {
            if (key.length() != 1) throw new JsonException("Map key '" + key + "' is not a single char");
            return key.charAt(0);
        }
        if (k.isEnum()) {
            try {
                return Enum.valueOf((Class<? extends Enum>) k, key);
            } catch (IllegalArgumentException e) {
                throw new JsonException("No enum constant " + key + " in " + k.getCanonicalName() + ".");
            }
        }
        if (k == UUID.class)        return UUID.fromString(key);
        if (k == BigInteger.class)  return new BigInteger(key);
        if (k == BigDecimal.class)  return new BigDecimal(key);
        throw new JsonException("Unsupported map key type " + k.getName());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static JsonReadCursor subCursor(JsonReadCursor cursor, Slot slot) {
        return slot == Slot.FIELD ? cursor.fieldValueCursor() : cursor.elementValueCursor();
    }

    private static boolean isNullValue(JsonReadCursor cursor, Slot slot) {
        ByteSlice raw = slot == Slot.FIELD ? cursor.fieldValue() : cursor.elementValue();
        return raw.length() == 4
                && raw.byteAt(0) == 'n' && raw.byteAt(1) == 'u'
                && raw.byteAt(2) == 'l' && raw.byteAt(3) == 'l';
    }

    private static Class<?> rawClass(Type t) {
        if (t instanceof Class<?> c) return c;
        if (t instanceof ParameterizedType pt) return rawClass(pt.getRawType());
        if (t instanceof GenericArrayType ga) return Array.newInstance(rawClass(ga.getGenericComponentType()), 0).getClass();
        if (t instanceof WildcardType w) {
            Type[] upper = w.getUpperBounds();
            return upper.length > 0 ? rawClass(upper[0]) : Object.class;
        }
        if (t instanceof TypeVariable<?> tv) {
            Type[] bounds = tv.getBounds();
            return bounds.length > 0 ? rawClass(bounds[0]) : Object.class;
        }
        return Object.class;
    }

    private static Type typeArg(Type t, int i) {
        if (t instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > i) return args[i];
        }
        return Object.class;
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

    private static Map<String, Integer> buildIndex(List<ReflectionMetadata.Property> props) {
        Map<String, Integer> map = new HashMap<>(props.size() * 2);
        for (int i = 0; i < props.size(); i++) map.put(props.get(i).name(), i);
        return map;
    }
}