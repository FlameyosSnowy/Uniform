package io.github.flameyossnowy.uniform.json.reflect;

import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapper;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;
import io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper;

import java.lang.classfile.ClassFile;
import java.lang.classfile.attribute.SourceFileAttribute;
import java.lang.constant.*;
import java.lang.invoke.*;

import static java.lang.classfile.ClassFile.*;
import static java.lang.constant.ConstantDescs.*;

public final class ReflectionMapperFactory {
    private static final ClassDesc CD_JsonAdapter = ClassDesc.of(JsonAdapter.class.getName());
    private static final ClassDesc CD_JsonReadCursor =
            ClassDesc.of("io.github.flameyossnowy.uniform.json.parser.JsonReadCursor");
    private static final ClassDesc CD_JsonStringWriter =
            ClassDesc.of("io.github.flameyossnowy.uniform.json.writers.JsonStringWriter");
    private static final ClassDesc CD_JsonMapper =
            ClassDesc.of("io.github.flameyossnowy.uniform.json.mappers.JsonMapper");
    private static final ClassDesc CD_JsonWriterMapper =
            ClassDesc.of("io.github.flameyossnowy.uniform.json.mappers.JsonWriterMapper");
    private static final ClassDesc CD_Object = ClassDesc.of("java.lang.Object");

    private ReflectionMapperFactory() {}

    @SuppressWarnings("unchecked")
    public static <T> JsonMapper<T> buildReader(Class<T> type) {
        ReflectionMetadata metadata = ReflectionMetadata.of(type);
        byte[] bytecode = generateReader(type, metadata);
        Class<?> generated = loadHidden(type, bytecode);

        try {
            JsonMapper<T> mapper = (JsonMapper<T>) generated.getDeclaredConstructor().newInstance();
            return JsonMapperRegistry.registerReflectionReader(type, mapper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate generated reader for " + type, e);
        }
    }

    @SuppressWarnings("unchecked")
    public static <T> JsonWriterMapper<T> buildWriter(Class<T> type) {
        ReflectionMetadata metadata = ReflectionMetadata.of(type);
        byte[] bytecode = generateWriter(type, metadata);
        Class<?> generated = loadHidden(type, bytecode);

        try {
            JsonWriterMapper<T> mapper = (JsonWriterMapper<T>) generated.getDeclaredConstructor().newInstance();
            return JsonMapperRegistry.registerReflectionWriter(type, mapper);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to instantiate generated writer for " + type, e);
        }
    }

    private static byte[] generateReader(Class<?> type, ReflectionMetadata metadata) {
        ClassFile classFile = ClassFile.of();
        String generatedName = generatedClassName(type, "Reader");
        ClassDesc generatedClass = ClassDesc.of(generatedName.replace('/', '.'));
        ClassDesc targetClass = ClassDesc.of(type.getName());
        ClassDesc metadataClass = ClassDesc.of(ReflectionMetadata.class.getName());
        ClassDesc readDelegate = ClassDesc.of(ReflectionReadDelegate.class.getName());

        return classFile.build(generatedClass, classBuilder -> {
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC);
            classBuilder.withInterfaceSymbols(CD_JsonMapper);
            classBuilder.withSuperclass(CD_Object);
            classBuilder.with(SourceFileAttribute.of(generatedName + ".java"));
            classBuilder.withField("META", metadataClass, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

            classBuilder.withMethod(CLASS_INIT_NAME, MethodTypeDesc.of(CD_void), ACC_STATIC, methodBuilder -> {
                methodBuilder.withCode(code -> {
                    code.ldc(targetClass);
                    code.invokestatic(
                            metadataClass,
                            "of",
                            MethodTypeDesc.of(metadataClass, ClassDesc.of(Class.class.getName()))
                    );
                    code.putstatic(generatedClass, "META", metadataClass);
                    code.return_();
                });
            });

            classBuilder.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void), ACC_PUBLIC, methodBuilder -> {
                methodBuilder.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void));
                    code.return_();
                });
            });

            // map(JsonAdapter adapter, JsonReadCursor cursor)
            classBuilder.withMethod(
                    "map",
                    MethodTypeDesc.of(CD_Object, CD_JsonAdapter, CD_JsonReadCursor),
                    ACC_PUBLIC,
                    methodBuilder -> methodBuilder.withCode(code -> {
                        code.aload(1); // adapter
                        code.getstatic(generatedClass, "META", metadataClass);
                        code.aload(2); // cursor
                        code.invokestatic(
                                readDelegate,
                                "read",
                                MethodTypeDesc.of(CD_Object, CD_JsonAdapter, metadataClass, CD_JsonReadCursor)
                        );
                        code.areturn();
                    })
            );
        });
    }

    private static byte[] generateWriter(Class<?> type, ReflectionMetadata metadata) {
        ClassFile classFile = ClassFile.of();
        String generatedName = generatedClassName(type, "Writer");
        ClassDesc generatedClass = ClassDesc.of(generatedName.replace('/', '.'));
        ClassDesc targetClass = ClassDesc.of(type.getName());
        ClassDesc metadataClass = ClassDesc.of(ReflectionMetadata.class.getName());
        ClassDesc writeDelegate = ClassDesc.of(ReflectionWriteDelegate.class.getName());

        return classFile.build(generatedClass, classBuilder -> {
            classBuilder.withFlags(ACC_PUBLIC | ACC_FINAL | ACC_SYNTHETIC);
            classBuilder.withInterfaceSymbols(CD_JsonWriterMapper);
            classBuilder.withSuperclass(CD_Object);
            classBuilder.withField("META", metadataClass, ACC_PUBLIC | ACC_STATIC | ACC_FINAL);

            classBuilder.withMethod(CLASS_INIT_NAME, MethodTypeDesc.of(CD_void), ACC_STATIC, methodBuilder -> {
                methodBuilder.withCode(code -> {
                    code.ldc(targetClass);
                    code.invokestatic(
                            metadataClass,
                            "of",
                            MethodTypeDesc.of(metadataClass, ClassDesc.of(Class.class.getName()))
                    );
                    code.putstatic(generatedClass, "META", metadataClass);
                    code.return_();
                });
            });

            classBuilder.withMethod(INIT_NAME, MethodTypeDesc.of(CD_void), ACC_PUBLIC, methodBuilder -> {
                methodBuilder.withCode(code -> {
                    code.aload(0);
                    code.invokespecial(CD_Object, INIT_NAME, MethodTypeDesc.of(CD_void));
                    code.return_();
                });
            });

            // writeTo(JsonAdapter adapter, JsonStringWriter out, Object value)
            classBuilder.withMethod(
                    "writeTo",
                    MethodTypeDesc.of(CD_void, CD_JsonAdapter, CD_JsonStringWriter, CD_Object),
                    ACC_PUBLIC,
                    methodBuilder -> methodBuilder.withCode(code -> {
                        code.aload(1); // adapter
                        code.getstatic(generatedClass, "META", metadataClass);
                        code.aload(2); // writer
                        code.aload(3); // value
                        code.invokestatic(
                                writeDelegate,
                                "write",
                                MethodTypeDesc.of(CD_void, CD_JsonAdapter, metadataClass,
                                        CD_JsonStringWriter, CD_Object)
                        );
                        code.return_();
                    })
            );
        });
    }

    private static Class<?> loadHidden(Class<?> host, byte[] bytecode) {
        try {
            MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(host, MethodHandles.lookup());
            return lookup.defineHiddenClass(
                    bytecode,
                    true,
                    MethodHandles.Lookup.ClassOption.NESTMATE,
                    MethodHandles.Lookup.ClassOption.STRONG
            ).lookupClass();
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot define hidden class for " + host + ". Add --add-opens if running on the module path.",
                    e
            );
        }
    }

    private static String generatedClassName(Class<?> type, String suffix) {
        String packageName = type.getPackageName().replace('.', '/');
        return packageName + "/" + type.getSimpleName() + "$$Reflect" + suffix + "__Uniform_" + suffix;
    }
}
