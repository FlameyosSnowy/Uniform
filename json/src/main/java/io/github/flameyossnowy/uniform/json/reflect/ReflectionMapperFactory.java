package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.json.mappers.JsonMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

/**
 * Generates a concrete {@link JsonMapper} and {@link JsonWriterMapper} for a
 * target class at runtime using the Java 21 ClassFile API, then loads it as a
 * hidden class so it can be GC'd if the target class is unloaded.
 *
 * <p>Generated classes are cached in {@link JsonMapperRegistry} — subsequent
 * lookups are as fast as compile-time generated mappers.
 */
public final class ReflectionMapperFactory {
    // Descriptor constants we'll use repeatedly
    private static final ClassDesc CD_JsonReadCursor   = ClassDesc.of("io.github.flameyossnowy.uniform.json.parser.JsonReadCursor");
    private static final ClassDesc CD_JsonStringWriter = ClassDesc.of("io.github.flameyossnowy.uniform.json.writers.JsonStringWriter");
    private static final ClassDesc CD_JsonMapper       = ClassDesc.of("io.github.flameyossnowy.uniform.json.mappers.JsonMapper");
    private static final ClassDesc CD_JsonWriterMapper = ClassDesc.of("io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper");
    private static final ClassDesc CD_JsonMapperRegistry = ClassDesc.of("io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry");
    private static final ClassDesc CD_Object           = ClassDesc.of("java.lang.Object");
    private static final ClassDesc CD_String           = ClassDesc.of("java.lang.String");

    private ReflectionMapperFactory() {}

    /**
     * Builds, loads, registers, and returns a {@link JsonMapper} for {@code type}.
     * Thread-safe — concurrent callers for the same type will both generate but
     * only one will win the {@code putIfAbsent} in the registry.
     */
    @SuppressWarnings("unchecked")
    public static <T> JsonMapper<T> buildReader(Class<T> type) {
        ReflectionMetadata meta = ReflectionMetadata.of(type);

        byte[] bytecode = generateReader(type, meta);
        Class<?> generated = loadHidden(type, bytecode);

        try {
            JsonMapper<T> mapper = (JsonMapper<T>) generated.getDeclaredConstructor().newInstance();
            JsonMapperRegistry.registerReader(type, mapper);
            return mapper;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate generated reader for " + type, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> JsonWriterMapper<T> buildWriter(Class<T> type) {
        ReflectionMetadata meta = ReflectionMetadata.of(type);

        byte[] bytecode = generateWriter(type, meta);
        Class<?> generated = loadHidden(type, bytecode);

        try {
            JsonWriterMapper<T> mapper = (JsonWriterMapper<T>) generated.getDeclaredConstructor().newInstance();
            JsonMapperRegistry.registerWriter(type, mapper);
            return mapper;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate generated writer for " + type, e);
        }
    }

    // -------------------------------------------------------------------------
    // Reader bytecode generation
    // -------------------------------------------------------------------------

    /**
     * Generates a class equivalent to:
     *
     * <pre>{@code
     * public final class GeneratedReader$$Foo implements JsonMapper<Foo> {
     *     public Foo map(JsonReadCursor cursor) {
     *         cursor.enterObject();
     *         // for records:
     *         Type1 prop1 = <default>; Type2 prop2 = <default>; ...
     *         while (cursor.nextField()) {
     *             String name = cursor.fieldNameAsString();
     *             if ("prop1".equals(name)) { prop1 = cursor.fieldValueParseXxx(); }
     *             else if ("prop2".equals(name)) { ... }
     *             else { cursor.skipFieldValue(); }
     *         }
     *         return new Foo(prop1, prop2, ...);   // record
     *         // OR
     *         Foo obj = new Foo();
     *         while (...) { if (...) { obj.setProp1(...); } }
     *         return obj;
     *     }
     * }
     * }</pre>
     *
     * For simplicity the generated code uses the reflection-metadata at runtime
     * via a static field holding the ReflectionMetadata, keeping the bytecode
     * small and correct. The hot path (field-name dispatch) is a cascade of
     * String.equals() — identical to what the annotation processor generates.
     */
    private static byte[] generateReader(Class<?> type, ReflectionMetadata meta) {
        ClassFile cf = ClassFile.of();
        String generatedName = generatedClassName(type, "Reader");
        ClassDesc cdGenerated = ClassDesc.of(generatedName.replace('/', '.'));
        ClassDesc cdTarget    = ClassDesc.of(type.getName());

        return cf.build(cdGenerated, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC);
            clb.withInterfaceSymbols(CD_JsonMapper);
            clb.withSuperclass(CD_Object);
            clb.with(SourceFileAttribute.of(generatedName + ".java"));

            // Static field: ReflectionMetadata META
            ClassDesc cdMeta = ClassDesc.of(ReflectionMetadata.class.getName());
            clb.withField("META", cdMeta, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

            // Static initializer: META = ReflectionMetadata.of(Foo.class)
            clb.withMethod(CLASS_INIT_NAME, MethodTypeDesc.of(CD_void), ACC_STATIC, mb -> {
                mb.withCode(cb -> {
                    cb.ldc(cdTarget);
                    cb.invokestatic(
                        cdMeta,
                        "of",
                        MethodTypeDesc.of(cdMeta, ClassDesc.of(Class.class.getName()))
                    );
                    cb.putstatic(cdGenerated, "META", cdMeta);
                    cb.return_();
                });
            });

            // No-arg constructor
            clb.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void), ACC_PUBLIC, mb -> {
                mb.withCode(cb -> {
                    cb.aload(0);
                    cb.invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void));
                    cb.return_();
                });
            });

            // map(JsonReadCursor) method — delegates to a static helper to keep
            // the generated bytecode simple and avoid verifier issues with
            // complex control flow across the interface bridge.
            clb.withMethod("map", MethodTypeDesc.of(CD_Object, CD_JsonReadCursor),
                ACC_PUBLIC, mb -> {
                mb.withCode(cb -> {
                    // Delegate to ReflectionReadDelegate.read(META, cursor)
                    cb.getstatic(cdGenerated, "META", cdMeta);
                    cb.aload(1); // cursor
                    cb.invokestatic(
                        ClassDesc.of(ReflectionReadDelegate.class.getName()),
                        "read",
                        MethodTypeDesc.of(CD_Object, cdMeta, CD_JsonReadCursor)
                    );
                    cb.areturn();
                });
            });
        });
    }

    // -------------------------------------------------------------------------
    // Writer bytecode generation
    // -------------------------------------------------------------------------

    private static byte[] generateWriter(Class<?> type, ReflectionMetadata meta) {
        ClassFile cf = ClassFile.of();
        String generatedName = generatedClassName(type, "Writer");
        ClassDesc cdGenerated = ClassDesc.of(generatedName.replace('/', '.'));
        ClassDesc cdTarget    = ClassDesc.of(type.getName());
        ClassDesc cdMeta      = ClassDesc.of(ReflectionMetadata.class.getName());

        return cf.build(cdGenerated, clb -> {
            clb.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC);
            clb.withInterfaceSymbols(CD_JsonWriterMapper);
            clb.withSuperclass(CD_Object);

            clb.withField("META", cdMeta, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

            clb.withMethod(CLASS_INIT_NAME, MethodTypeDesc.of(CD_void), ACC_STATIC, mb -> {
                mb.withCode(cb -> {
                    cb.ldc(cdTarget);
                    cb.invokestatic(
                        cdMeta, "of",
                        MethodTypeDesc.of(cdMeta, ClassDesc.of(Class.class.getName()))
                    );
                    cb.putstatic(cdGenerated, "META", cdMeta);
                    cb.return_();
                });
            });

            clb.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void), ACC_PUBLIC, mb -> {
                mb.withCode(cb -> {
                    cb.aload(0);
                    cb.invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void));
                    cb.return_();
                });
            });

            // writeTo(JsonStringWriter, Object)
            clb.withMethod("writeTo",
                MethodTypeDesc.of(CD_void, CD_JsonStringWriter, CD_Object),
                ACC_PUBLIC, mb -> {
                mb.withCode(cb -> {
                    cb.getstatic(cdGenerated, "META", cdMeta);
                    cb.aload(1); // writer
                    cb.aload(2); // value
                    cb.invokestatic(
                        ClassDesc.of(ReflectionWriteDelegate.class.getName()),
                        "write",
                        MethodTypeDesc.of(CD_void, cdMeta, CD_JsonStringWriter, CD_Object)
                    );
                    cb.return_();
                });
            });
        });
    }

    // -------------------------------------------------------------------------
    // Hidden class loading
    // -------------------------------------------------------------------------

    private static Class<?> loadHidden(Class<?> host, byte[] bytecode) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(
                host, MethodHandles.lookup()
            );
            return lookup.defineHiddenClass(
                bytecode,
                true,  // initialize immediately
                MethodHandles.Lookup.ClassOption.NESTMATE,
                MethodHandles.Lookup.ClassOption.STRONG   // keep alive as long as host
            ).lookupClass();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                "Cannot define hidden class for " + host + ". " +
                "Add --add-opens if running on the module path.", e);
        }
    }

    private static String generatedClassName(Class<?> type, String suffix) {
        String pkg = type.getPackageName().replace('.', '/');
        return pkg + "/"
            + type.getSimpleName() + "$$Reflect" + suffix + "__Uniform_" + suffix;
    }
}