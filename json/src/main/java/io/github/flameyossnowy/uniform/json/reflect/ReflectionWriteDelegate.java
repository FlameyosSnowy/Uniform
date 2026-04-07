package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.writers.JsonStringWriter;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public final class ReflectionWriteDelegate {
    private ReflectionWriteDelegate() {}

    public static void write(ReflectionMetadata meta, JsonStringWriter out, Object value) {
        if (value == null) {
            out.nullValue();
            return;
        }

        List<ReflectionMetadata.Property> props = meta.properties;
        out.beginObject();

        for (ReflectionMetadata.Property prop : props) {
            Object propValue;
            try {
                propValue = prop.read(value);
            } catch (ReflectiveOperationException e) {
                continue;
            }

            out.name(prop.name());
            writeValue(out, propValue, prop.type());
        }

        out.endObject();
    }

    private static void writeValue(JsonStringWriter out, Object value, Class<?> declaredType) {
        if (value == null) {
            out.nullValue();
            return;
        }

        try {
            Class<?> type = value.getClass();

            if (type == String.class)  { out.value((String) value); return; }
            if (type == Integer.class) { out.value((Integer) value); return; }
            if (type == Long.class)    { out.value((Long) value); return; }
            if (type == Double.class)  { out.value((Double) value); return; }
            if (type == Float.class)   { out.value((Float) value); return; }

            if (type == Boolean.class) {
                out.value((Boolean) value);
                return;
            }

            if (type == Short.class) {
                out.value(((Short) value).intValue());
                return;
            }

            if (type == Byte.class) {
                out.value(((Byte) value).intValue());
                return;
            }

            if (type.isArray()) {
                writeArray(out, value, type.getComponentType());
                return;
            }

            if (value instanceof Collection<?> col) {
                writeCollection(out, col);
                return;
            }

            if (value instanceof Map<?, ?> map) {
                writeMap(out, map);
                return;
            }

            JsonWriterMapper<Object> writer =
                (JsonWriterMapper<Object>) JsonMapperRegistry.getWriter(type);

            if (writer == null && isReflectable(type)) {
                writer = (JsonWriterMapper<Object>) ReflectionMapperFactory.buildWriter(type);
            }

            if (writer != null) {
                writer.writeTo(out, value);
                return;
            }

        } catch (Exception ignored) {
            // fallthrough
        }

        // 🚨 GUARANTEED OUTPUT
        out.nullValue();
    }

    private static void writeArray(JsonStringWriter out, Object array, Class<?> componentType) {
        out.beginArray();

        int len = Array.getLength(array);
        for (int i = 0; i < len; i++) {
            Object elem = Array.get(array, i);
            writeValue(out, elem, componentType);
        }

        out.endArray();
    }

    private static void writeCollection(JsonStringWriter out, Collection<?> col) {
        out.beginArray();

        for (Object elem : col) {
            if (elem == null) {
                out.nullValue();
            } else {
                writeValue(out, elem, elem.getClass());
            }
        }

        out.endArray();
    }

    private static void writeMap(JsonStringWriter out, Map<?, ?> map) {
        out.beginObject();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            out.name(String.valueOf(entry.getKey()));
            Object v = entry.getValue();
            if (v == null) out.nullValue();
            else writeValue(out, v, v.getClass());
        }
        out.endObject();
    }

    private static boolean isReflectable(Class<?> type) {
        ClassLoader cl = type.getClassLoader();
        if (cl == null) return false;
        return !cl.getClass().getName()
            .equals("jdk.internal.loader.ClassLoaders$PlatformClassLoader");
    }
}