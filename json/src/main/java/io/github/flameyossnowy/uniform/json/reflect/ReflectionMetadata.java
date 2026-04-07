package io.github.flameyossnowy.uniform.json.reflect;

import java.lang.reflect.*;
import java.util.*;

/**
 * Inspects a class once at first use and caches:
 *  - all readable properties (field / record component / getter)
 *  - all writable properties (field / setter / record constructor param)
 * Priority: record components > getters/setters > bare fields.
 */
public final class ReflectionMetadata {

    public record Property(
        String    name,
        Class<?>  type,
        Type      genericType,   // for best-effort List<X> support
        Member    reader,        // Field or Method (getter / record accessor)
        Member    writer,        // Field or Method (setter) — null for records
        Class<?>  owner
    ) {
        public Object read(Object instance) throws ReflectiveOperationException {
            return switch (reader) {
                case Field  f -> f.get(instance);
                case Method m -> m.invoke(instance);
                default -> throw new IllegalStateException("Unexpected reader: " + reader);
            };
        }

        /** For records, writer is null — use the canonical constructor instead. */
        public void write(Object instance, Object value) throws ReflectiveOperationException {
            switch (writer) {
                case Field  f -> f.set(instance, value);
                case Method m -> m.invoke(instance, value);
                case null     -> throw new IllegalStateException(
                    "Property " + name + " is read-only (record field). " +
                    "Use the canonical constructor.");
                default -> throw new IllegalStateException("Unexpected writer: " + writer);
            }
        }
    }

    public final Class<?>          type;
    public final boolean           isRecord;
    public final List<Property>    properties;
    /** For records: the canonical constructor, parameter order matches properties. */
    public final Constructor<?>    canonicalConstructor;
    /** For regular classes: the no-arg constructor. */
    public final Constructor<?>    noArgConstructor;

    private static final Map<Class<?>, ReflectionMetadata> CACHE =
        new java.util.concurrent.ConcurrentHashMap<>();

    public static ReflectionMetadata of(Class<?> type) {
        return CACHE.computeIfAbsent(type, ReflectionMetadata::inspect);
    }

    private ReflectionMetadata(
        Class<?>       type,
        boolean        isRecord,
        List<Property> properties,
        Constructor<?> canonicalConstructor,
        Constructor<?> noArgConstructor
    ) {
        this.type                 = type;
        this.isRecord             = isRecord;
        this.properties           = properties;
        this.canonicalConstructor = canonicalConstructor;
        this.noArgConstructor     = noArgConstructor;
    }

    private static ReflectionMetadata inspect(Class<?> type) {
        boolean isRecord = type.isRecord();

        if (isRecord) {
            return inspectRecord(type);
        } else {
            return inspectBean(type);
        }
    }

    private static ReflectionMetadata inspectRecord(Class<?> type) {
        RecordComponent[] components = type.getRecordComponents();
        List<Property> props = new ArrayList<>(components.length);
        Class<?>[] ctorTypes = new Class<?>[components.length];

        for (int i = 0; i < components.length; i++) {
            RecordComponent rc = components[i];
            Method accessor = rc.getAccessor();
            accessor.setAccessible(true);

            props.add(new Property(
                rc.getName(),
                rc.getType(),
                rc.getGenericType(),
                accessor,
                null,
                type
            ));
            ctorTypes[i] = rc.getType();
        }

        Constructor<?> canonical = null;
        try {
            canonical = type.getDeclaredConstructor(ctorTypes);
            canonical.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Record " + type + " has no canonical constructor", e);
        }

        return new ReflectionMetadata(type, true, Collections.unmodifiableList(props), canonical, null);
    }

    private static ReflectionMetadata inspectBean(Class<?> type) {
        // Collect getter/setter pairs first, then fall back to bare fields
        Map<String, Method>  getters = new LinkedHashMap<>();
        Map<String, Method>  setters = new LinkedHashMap<>();
        Map<String, Field>   fields  = new LinkedHashMap<>();

        // Walk the class hierarchy for fields
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod)) continue;
                if (!fields.containsKey(f.getName())) {
                    f.setAccessible(true);
                    fields.put(f.getName(), f);
                }
            }
            for (Method m : c.getDeclaredMethods()) {
                int mod = m.getModifiers();
                if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) continue;
                String name = m.getName();
                if (name.startsWith("get") && name.length() > 3
                        && m.getParameterCount() == 0) {
                    String prop = decapitalize(name.substring(3));
                    getters.putIfAbsent(prop, m);
                } else if (name.startsWith("is") && name.length() > 2
                        && m.getParameterCount() == 0
                        && (m.getReturnType() == boolean.class
                            || m.getReturnType() == Boolean.class)) {
                    String prop = decapitalize(name.substring(2));
                    getters.putIfAbsent(prop, m);
                } else if (name.startsWith("set") && name.length() > 3
                        && m.getParameterCount() == 1) {
                    String prop = decapitalize(name.substring(3));
                    setters.putIfAbsent(prop, m);
                }
            }
        }

        // Merge: prefer getter/setter pair, fall back to field
        List<Property> props = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Getters drive the property set
        for (Map.Entry<String, Method> e : getters.entrySet()) {
            String name   = e.getKey();
            Method getter = e.getValue();
            Method setter = setters.get(name);
            Field  field  = fields.get(name);

            Type genericType = (field != null) ? field.getGenericType() : getter.getGenericReturnType();

            props.add(new Property(
                name,
                getter.getReturnType(),
                genericType,
                getter,
                setter,  // may be null for read-only bean properties
                type
            ));
            seen.add(name);
        }

        Class<?>[] ctorTypes = new Class<?>[fields.size()];
        // Bare fields not covered by a getter
        int i = 0;
        for (Map.Entry<String, Field> e : fields.entrySet()) {
            if (seen.contains(e.getKey())) continue;
            Field f = e.getValue();
            props.add(new Property(
                f.getName(),
                f.getType(),
                f.getGenericType(),
                f,
                Modifier.isFinal(f.getModifiers()) ? null : f,
                type
            ));
            ctorTypes[i] = f.getType();
            i++;
        }

        Constructor<?> noArg = null;
        try {
            noArg = type.getDeclaredConstructor();
            noArg.setAccessible(true);
        } catch (NoSuchMethodException ignored) {
        }

        Constructor<?> canonical = null;
        try {
            canonical = type.getDeclaredConstructor(ctorTypes);
            canonical.setAccessible(true);
        } catch (NoSuchMethodException _) {
        }

        return new ReflectionMetadata(
            type, false,
            Collections.unmodifiableList(props),
            canonical, noArg
        );
    }

    private static String decapitalize(String s) {
        if (s.isEmpty()) return s;
        char[] c = s.toCharArray();
        c[0] = Character.toLowerCase(c[0]);
        return new String(c);
    }
}