package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.writers.JsonStringWriter;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;

public final class ReflectionWriteDelegate {
    private static final byte[] NULL = "null".getBytes(StandardCharsets.UTF_8);

    private ReflectionWriteDelegate() {}

    public static void write(ReflectionMetadata meta, JsonStringWriter out, Object value) {
        if (value == null) {
            out.writeRaw(NULL);
            return;
        }

        List<ReflectionMetadata.Property> props = meta.properties;
        out.write('{');

        boolean first = true;
        for (ReflectionMetadata.Property prop : props) {
            Object propValue;
            try {
                propValue = prop.read(value);
            } catch (ReflectiveOperationException e) {
                continue; // skip unreadable properties gracefully
            }

            if (!first) out.write(',');
            first = false;

            out.writeString(prop.name());
            out.write(':');
            writeValue(out, propValue, prop.type());
        }

        out.write('}');
    }

    @SuppressWarnings("unchecked")
    private static <T> void writeValue(JsonStringWriter out, T value, Class<?> declaredType) {
        if (value == null) {
            out.writeRaw(NULL);
            return;
        }

        Class<?> type = value.getClass();

        if (type == String.class)             { out.writeString((String) value); return; }
        if (type == Integer.class
         || type == int.class)                { out.writeInt((int) value); return; }
        if (type == Long.class
         || type == long.class)               { out.writeLong((long) value); return; }
        if (type == Double.class
         || type == double.class)             { out.writeDouble((double) value); return; }
        if (type == Float.class
         || type == float.class)              { out.writeFloat((float) value); return; }
        if (type == Boolean.class
         || type == boolean.class)            { out.writeString(value.toString()); return; }
        if (type == Short.class
         || type == short.class)              { out.writeInt((int) value); return; }
        if (type == Byte.class
         || type == byte.class)               { out.writeInt((int) value); return; }

        if (value instanceof Collection<?> col) {
            writeCollection(out, col);
            return;
        }

        JsonWriterMapper<Object> writer =
            (JsonWriterMapper<Object>) JsonMapperRegistry.getWriter(type);
        if (writer == null) {
            writer = (JsonWriterMapper<Object>) ReflectionMapperFactory.buildWriter(type);
        }
        writer.writeTo(out, value);
    }

    private static void writeCollection(JsonStringWriter out, Collection<?> col) {
        out.write('[');
        boolean first = true;
        for (Object elem : col) {
            if (!first) out.write(',');
            first = false;
            if (elem == null) {
                out.writeRaw(NULL);
            } else {
                writeValue(out, elem, elem.getClass());
            }
        }
        out.write(']');
    }
}