package io.github.flameyossnowy.uniform.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.AnnotationSpec;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.FieldSpec;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import io.github.flameyossnowy.uniform.core.CollectionKind;
import io.github.flameyossnowy.uniform.core.annotations.ContextDynamicSupplier;
import io.github.flameyossnowy.uniform.core.annotations.IgnoreSerializedField;
import io.github.flameyossnowy.uniform.core.annotations.Resolves;
import io.github.flameyossnowy.uniform.core.annotations.SerializedCreator;
import io.github.flameyossnowy.uniform.core.annotations.SerializedField;
import io.github.flameyossnowy.uniform.core.annotations.SerializedName;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
import javax.annotation.processing.Generated;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.Processor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.MirroredTypeException;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.RecordComponentElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SuppressWarnings("ObjectAllocationInLoop")
@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_17)
public final class UniformJsonProcessor extends AbstractProcessor {
    private static final Set<String> CORE_RESOLVED_FQCNS = Set.of(
        "java.math.BigInteger", "java.math.BigDecimal", "java.util.UUID",
        "java.net.URI", "java.net.URL", "java.nio.file.Path",
        "java.time.LocalDate", "java.time.LocalTime", "java.time.LocalDateTime",
        "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
        "java.time.Instant", "java.time.Duration", "java.time.Period",
        "java.time.Year", "java.time.YearMonth", "java.time.MonthDay",
        "java.time.Month", "java.time.ZoneId", "java.util.TimeZone"
    );

    private static final ClassName CORE_REGISTRY =
        ClassName.get("me.flame.uniform.json.resolvers", "CoreTypeResolverRegistry");
    private static final ClassName CORE_RESOLVER =
        ClassName.get("me.flame.uniform.json.resolvers", "CoreTypeResolver");
    private static final ClassName JSON_VALUE =
        ClassName.get("me.flame.uniform.json.dom", "JsonValue");

    private static final String FIELD_TRUE  = "__BTRUE";
    private static final String FIELD_FALSE = "__BFALSE";
    private static final String FIELD_NULL  = "__BNULL";

    // Objects with <= SMALL_OBJECT_THRESHOLD fields use linear if/else dispatch,
    // skipping the FNV-1a hash entirely.
    private static final int SMALL_OBJECT_THRESHOLD = 4;

    // Prefix for reader-side FNV-1a hash constants: static final int __H_fieldName = <hash>;
    private static final String HASH_CONST_PREFIX = "__H_";

    private Elements elements;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elements = processingEnv.getElementUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> supported = new HashSet<>(8);
        supported.add(SerializedObject.class.getCanonicalName());
        supported.add(SerializedField.class.getCanonicalName());
        supported.add(SerializedName.class.getCanonicalName());
        supported.add(IgnoreSerializedField.class.getCanonicalName());
        supported.add(SerializedCreator.class.getCanonicalName());
        supported.add(ContextDynamicSupplier.class.getCanonicalName());
        supported.add(Resolves.class.getCanonicalName());
        return supported;
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        Map<String, String> dynamicSuppliers = collectDynamicSuppliers(roundEnv);

        Set<? extends Element> roots = roundEnv.getElementsAnnotatedWith(SerializedObject.class);
        if (roots.isEmpty()) return false;

        List<ClassName> generatedModules = new ArrayList<>(16);
        Set<String> visited = new HashSet<>(16);
        Deque<TypeElement> queue = new ArrayDeque<>(16);

        for (Element element : roots) {
            if (element.getKind() != ElementKind.CLASS && element.getKind() != ElementKind.RECORD) continue;
            queue.add((TypeElement) element);
        }

        while (!queue.isEmpty()) {
            TypeElement typeElement = queue.removeFirst();
            String qn = elements.getBinaryName(typeElement).toString();
            if (!visited.add(qn)) continue;
            try {
                GeneratedType generated = generateFor(typeElement, dynamicSuppliers, queue);
                generatedModules.add(generated.moduleClass);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        try {
            writeAggregatorModule(generatedModules);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    private Map<String, String> collectDynamicSuppliers(RoundEnvironment roundEnv) {
        Map<String, String> suppliers = new HashMap<>(16);
        for (Element element : roundEnv.getElementsAnnotatedWith(ContextDynamicSupplier.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;
            TypeElement supplierType = (TypeElement) element;
            ContextDynamicSupplier annotation = supplierType.getAnnotation(ContextDynamicSupplier.class);
            Objects.requireNonNull(annotation);
            TypeMirror declared = getClassValueMirror(annotation);
            if (declared == null || declared.getKind() != TypeKind.DECLARED) continue;
            TypeElement declaredType = (TypeElement) ((DeclaredType) declared).asElement();
            suppliers.put(elements.getBinaryName(declaredType).toString(),
                elements.getBinaryName(supplierType).toString());
        }
        return suppliers;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static TypeMirror getClassValueMirror(ContextDynamicSupplier ann) {
        try { ann.value(); return null; } catch (MirroredTypeException e) { return e.getTypeMirror(); }
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static TypeMirror getClassValueMirror(Resolves ann) {
        try { ann.value(); return null; } catch (MirroredTypeException e) { return e.getTypeMirror(); }
    }

    /**
     * @param accessor      Write accessor: field name, getter name, or setter name.
     * @param readAccessor  Non-null when accessKind==SETTER but the field has a getter.
     */
    private record Property(String javaName, String jsonName, TypeName typeName, TypeMirror typeMirror,
                            AccessKind accessKind, String accessor, String readAccessor,
                            boolean abstractOrInterface, String resolverFqcn) {}

    private enum AccessKind { FIELD, RECORD_COMPONENT, GETTER, SETTER }

    private record GeneratedType(ClassName moduleClass) {}

    private ExecutableElement findGetter(TypeElement type, String fieldName, TypeMirror fieldType) {
        String capitalized = Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
        Set<String> candidates = new HashSet<>();
        candidates.add("get" + capitalized);
        if (fieldType.getKind() == TypeKind.BOOLEAN
            || (fieldType.getKind() == TypeKind.DECLARED
            && elements.getBinaryName((TypeElement) ((DeclaredType) fieldType).asElement())
            .toString().equals("java.lang.Boolean"))) {
            candidates.add("is" + capitalized);
        }
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) e;
            if (!candidates.contains(m.getSimpleName().toString())) continue;
            if (m.getModifiers().contains(Modifier.STATIC)) continue;
            if (!m.getParameters().isEmpty()) continue;
            if (m.getReturnType().getKind() == TypeKind.VOID) continue;
            return m;
        }
        return null;
    }

    private boolean isCoreResolved(TypeMirror mirror) {
        if (mirror.getKind() != TypeKind.DECLARED) return false;
        Element e = ((DeclaredType) mirror).asElement();
        if (!(e instanceof TypeElement te)) return false;
        String fqcn = elements.getBinaryName(te).toString();
        if (CORE_RESOLVED_FQCNS.contains(fqcn)) return true;
        return te.getKind() == ElementKind.ENUM;
    }

    private boolean isCoreResolved(TypeName typeName) {
        if (!(typeName instanceof ClassName cn)) return false;
        return CORE_RESOLVED_FQCNS.contains(cn.canonicalName());
    }

    private static void emitCoreRead(CodeBlock.Builder cb, TypeName type, String varName,
                                     String cursorExpr, String valueMethod) {
        String jvVar = "__jv_" + varName;
        cb.addStatement("$T $L = $L.$L()", JSON_VALUE, jvVar, cursorExpr, valueMethod);
        cb.addStatement("$L = ($T) $T.INSTANCE.resolve($T.class).deserialize($L)",
            varName, type.box(), CORE_REGISTRY, type.box(), jvVar);
    }

    private static void emitCoreWrite(CodeBlock.Builder cb, TypeName type,
                                      String valueExpr, String jvVar) {
        cb.addStatement("$T $L = (($T<$T>) $T.INSTANCE.resolve($T.class)).serialize($L)",
            JSON_VALUE, jvVar, CORE_RESOLVER, type.box(), CORE_REGISTRY, type.box(), valueExpr);
        cb.addStatement("out.writeJsonValue($L)", jvVar);
    }

    private static ClassName readerNameFor(ClassName declared) {
        String flat = String.join("_", declared.simpleNames());
        return ClassName.get(declared.packageName() + ".generated", flat + "_JsonReader");
    }

    private static ClassName writerNameFor(ClassName declared) {
        String flat = String.join("_", declared.simpleNames());
        return ClassName.get(declared.packageName() + ".generated", flat + "_JsonWriter");
    }

    private static String fieldConstantName(String jsonName) {
        StringBuilder sb = new StringBuilder("__F_");
        for (int i = 0; i < jsonName.length(); i++) {
            char c = jsonName.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    /**
     * Returns the compile-time FNV-1a hash constant name for a JSON field name.
     * Generated into reader classes as: {@code private static final int __H_fieldName = <hash>;}
     */
    private static String hashConstantName(String jsonName) {
        StringBuilder sb = new StringBuilder(HASH_CONST_PREFIX);
        for (int i = 0; i < jsonName.length(); i++) {
            char c = jsonName.charAt(i);
            sb.append(Character.isJavaIdentifierPart(c) ? c : '_');
        }
        return sb.toString();
    }

    private static String fieldFragmentInitializer(String jsonName) {
        StringBuilder sb = new StringBuilder("{(byte)'\"'");
        for (int i = 0; i < jsonName.length(); i++) {
            char c = jsonName.charAt(i);
            sb.append(',');
            if (c >= 0x20 && c <= 0x7E && c != '\'' && c != '\\') {
                sb.append("(byte)'").append(c).append('\'');
            } else {
                sb.append("(byte)").append((int) c);
            }
        }
        sb.append(",(byte)'\"',(byte)':'}");
        return sb.toString();
    }

    private static long packAsciiLong(String s) {
        if (s.length() > 8) return -1L;
        long v = 0L;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) return -1L;
            v |= ((long) c) << (i * 8);
        }
        return v;
    }

    private GeneratedType generateFor(TypeElement type, Map<String, String> dynamicSuppliers,
                                      Deque<TypeElement> enqueue) throws IOException {
        ClassName target = ClassName.get(type);
        String pkg = target.packageName();
        String simple = target.simpleName();

        if (pkg.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                "Types annotated with @SerializedObject must be in a named package.", type);
            ClassName moduleName = ClassName.get("generated", simple + "_JsonModule");
            return new GeneratedType(moduleName);
        }

        String flatSimple = target.simpleNames().stream()
            .collect(java.util.stream.Collectors.joining("_"));
        String generatedPkg = pkg + ".generated";

        List<Property> properties = collectProperties(type, dynamicSuppliers, enqueue);

        ClassName readerName = ClassName.get(generatedPkg, flatSimple + "_JsonReader");
        ClassName writerName = ClassName.get(generatedPkg, flatSimple + "_JsonWriter");
        ClassName moduleName = ClassName.get(generatedPkg, flatSimple + "_JsonModule");

        writeReader(type, target, readerName, properties);
        writeWriterInternal(target, writerName, properties);
        writeModule(target, readerName, writerName, moduleName);

        return new GeneratedType(moduleName);
    }

    private List<Property> collectProperties(TypeElement type, Map<String, String> dynamicSuppliers,
                                             Deque<TypeElement> enqueue) {
        List<Property> props = new ArrayList<>(16);

        if (type.getKind() == ElementKind.RECORD) {
            for (Element e : type.getEnclosedElements()) {
                if (e.getKind() != ElementKind.RECORD_COMPONENT) continue;
                RecordComponentElement rc = (RecordComponentElement) e;
                if (rc.getAnnotation(IgnoreSerializedField.class) != null) continue;
                String javaName = rc.getSimpleName().toString();
                String jsonName = jsonNameFor(rc, javaName);
                props.add(buildProperty(type, rc, javaName, jsonName, rc.asType(),
                    AccessKind.RECORD_COMPONENT, javaName, null, dynamicSuppliers, enqueue));
            }
            return props;
        }

        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            VariableElement f = (VariableElement) e;
            if (f.getModifiers().contains(Modifier.STATIC)) continue;
            if (f.getAnnotation(IgnoreSerializedField.class) != null) continue;

            String javaName = f.getSimpleName().toString();
            String jsonName = jsonNameFor(f, javaName);
            boolean isPublic = f.getModifiers().contains(Modifier.PUBLIC);

            if (!isPublic) {
                ExecutableElement getter = findGetter(type, javaName, f.asType());
                if (getter != null) {
                    String getterName = getter.getSimpleName().toString();
                    props.add(buildProperty(type, f, javaName, jsonName, f.asType(),
                        AccessKind.GETTER, getterName, getterName, dynamicSuppliers, enqueue));
                } else {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Field '" + type.getSimpleName() + "." + javaName + "' is not public and has no "
                            + "accessible getter.", f);
                }
                continue;
            }
            props.add(buildProperty(type, f, javaName, jsonName, f.asType(),
                AccessKind.FIELD, javaName, null, dynamicSuppliers, enqueue));
        }

        Map<String, Property> propByJavaName = new LinkedHashMap<>(props.size());
        for (Property p : props) propByJavaName.put(p.javaName(), p);

        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) e;
            if (m.getModifiers().contains(Modifier.STATIC)) continue;
            if (m.getAnnotation(IgnoreSerializedField.class) != null) continue;

            String methodName = m.getSimpleName().toString();

            if (m.getParameters().isEmpty() && m.getReturnType().getKind() != TypeKind.VOID) {
                SerializedField sf = m.getAnnotation(SerializedField.class);
                SerializedName  sn = m.getAnnotation(SerializedName.class);
                if (sf == null && sn == null) continue;
                String javaName = methodName;
                String jsonName = jsonNameFor(m, javaName);
                props.add(buildProperty(type, m, javaName, jsonName, m.getReturnType(),
                    AccessKind.GETTER, javaName, javaName, dynamicSuppliers, enqueue));
                propByJavaName.put(javaName, props.get(props.size() - 1));
                continue;
            }

            if (m.getParameters().size() != 1) continue;
            if (m.getReturnType().getKind() != TypeKind.VOID) continue;

            TypeMirror paramType = m.getParameters().get(0).asType();
            SerializedField sf = m.getAnnotation(SerializedField.class);
            SerializedName  sn = m.getAnnotation(SerializedName.class);

            String targetFieldName = null;
            if (methodName.startsWith("set") && methodName.length() > 3) {
                targetFieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (sf != null || sn != null) {
                targetFieldName = methodName;
            }
            if (targetFieldName == null) continue;
            if (sf == null && sn == null && !propByJavaName.containsKey(targetFieldName)) continue;

            Property existing = propByJavaName.get(targetFieldName);
            if (existing != null) {
                String jsonName = (sf != null || sn != null)
                    ? jsonNameFor(m, existing.jsonName()) : existing.jsonName();
                String readAccessor = existing.readAccessor() != null ? existing.readAccessor()
                    : (existing.accessKind() == AccessKind.GETTER ? existing.accessor() : null);
                propByJavaName.put(targetFieldName, new Property(
                    existing.javaName(), jsonName, existing.typeName(), existing.typeMirror(),
                    AccessKind.SETTER, methodName, readAccessor,
                    existing.abstractOrInterface(), existing.resolverFqcn()));
            } else {
                String jsonName = jsonNameFor(m, targetFieldName);
                Property newProp = buildProperty(type, m, targetFieldName, jsonName,
                    paramType, AccessKind.SETTER, methodName, null, dynamicSuppliers, enqueue);
                propByJavaName.put(targetFieldName, newProp);
            }
        }

        props.clear();
        props.addAll(propByJavaName.values());
        return props;
    }

    private Property buildProperty(TypeElement owner, Element annotatedElement,
                                   String javaName, String jsonName, TypeMirror typeMirror,
                                   AccessKind accessKind, String accessor, String readAccessor,
                                   Map<String, String> dynamicSuppliers, Deque<TypeElement> enqueue) {
        TypeName typeName = TypeName.get(typeMirror);
        Property property = new Property(javaName, jsonName, typeName, typeMirror, accessKind,
            accessor, readAccessor, false, null);
        if (typeMirror.getKind() == TypeKind.ARRAY) return property;

        if (typeMirror.getKind() == TypeKind.DECLARED) {
            Element te = ((DeclaredType) typeMirror).asElement();
            if (te instanceof TypeElement typeElement) {
                if (collectionKind(typeMirror) != CollectionKind.NONE) {
                    for (TypeMirror arg : ((DeclaredType) typeMirror).getTypeArguments()) {
                        if (arg.getKind() == TypeKind.DECLARED) {
                            Element argElem = ((DeclaredType) arg).asElement();
                            if (argElem instanceof TypeElement argType && shouldEnqueueForCodegen(argType))
                                enqueue.add(argType);
                        }
                    }
                    return property;
                }
                if (isCoreResolved(typeMirror)) return property;

                boolean absOrIface = typeElement.getKind() == ElementKind.INTERFACE
                    || typeElement.getModifiers().contains(Modifier.ABSTRACT);
                String resolverFqcn = null;
                Resolves resolves = annotatedElement.getAnnotation(Resolves.class);
                if (resolves != null) {
                    TypeMirror mirror = getClassValueMirror(resolves);
                    if (mirror != null && mirror.getKind() == TypeKind.DECLARED) {
                        resolverFqcn = elements.getBinaryName(
                            (TypeElement) ((DeclaredType) mirror).asElement()).toString();
                    }
                }
                if (!absOrIface) {
                    if (shouldEnqueueForCodegen(typeElement)) enqueue.add(typeElement);
                } else {
                    String declaredFqcn = elements.getBinaryName(typeElement).toString();
                    boolean hasDynamic  = dynamicSuppliers.containsKey(declaredFqcn);
                    if (resolverFqcn == null && !hasDynamic) {
                        processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Interface/abstract property '" + owner.getSimpleName() + "." + javaName
                                + "' of type '" + declaredFqcn + "' requires @Resolves or a "
                                + "@ContextDynamicSupplier for that declared type.", annotatedElement);
                    }
                }
                return new Property(javaName, jsonName, typeName, typeMirror, accessKind, accessor,
                    readAccessor, absOrIface, resolverFqcn);
            }
        }
        return property;
    }

    private boolean shouldEnqueueForCodegen(TypeElement type) {
        String qn = elements.getBinaryName(type).toString();
        if (qn.startsWith("java.") || qn.startsWith("javax.") || qn.startsWith("jdk.")
            || qn.startsWith("sun.") || qn.startsWith("org.jetbrains.")) return false;
        return type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD;
    }

    private static String jsonNameFor(Element element, String fallback) {
        SerializedName sn = element.getAnnotation(SerializedName.class);
        if (sn != null && !sn.value().isBlank()) return sn.value();
        SerializedField sf = element.getAnnotation(SerializedField.class);
        if (sf != null && !sf.value().isBlank()) return sf.value();
        return fallback;
    }

    private void writeReader(TypeElement typeElement, ClassName target, ClassName readerName,
                             List<Property> props) throws IOException {
        ClassName jsonCursor = ClassName.get("me.flame.uniform.json.parser", "JsonReadCursor");
        ClassName jsonMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");
        ClassName jsonConfig = ClassName.get("me.flame.uniform.json", "JsonConfig");

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(readerName)
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "\"io.github.flameyossnowy.uniform.processor.UniformJsonProcessor\"")
                .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(jsonMapper, target))
            .addField(FieldSpec.builder(jsonConfig, "__config",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.VOLATILE).build())
            .addMethod(MethodSpec.methodBuilder("setConfig")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(jsonConfig, "cfg")
                .addStatement("__config = cfg")
                .build())
            .addField(ParameterizedTypeName.get(jsonMapper, target), "INSTANCE",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addStaticBlock(CodeBlock.builder()
                .addStatement("INSTANCE = new $T()", readerName)
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodSpec.methodBuilder("stripQuotes")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "s")
                .addStatement("if (s == null) return null")
                .addStatement("int len = s.length()")
                .addStatement("if (len >= 2 && s.charAt(0) == '\"' && s.charAt(len - 1) == '\"') return s.substring(1, len - 1)")
                .addStatement("return s")
                .build());

        // Emit compile-time FNV-1a hash constants for hash-switch dispatch
        // e.g. private static final int __H_id = -1305444774;
        // This lets the switch statement use human-readable names:
        //   case __H_id: if (cursor.fieldNameEquals("id")) { ... }
        // The JIT sees these as identical to inline integer literals.
        boolean useHashSwitch = props.size() > SMALL_OBJECT_THRESHOLD;
        if (useHashSwitch) {
            addHashConstantsTo(classBuilder, props);
        }

        ClassName concreteCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");

        MethodSpec readFields = MethodSpec.methodBuilder("readFields")
            .addModifiers(Modifier.PUBLIC) // public so cross-package generated readers can call it
            .returns(target)
            .addParameter(concreteCursor, "cursor")
            .addCode(buildReaderBody(typeElement, target, props))
            .build();

        MethodSpec map = MethodSpec.methodBuilder("map")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(target)
            .addParameter(jsonCursor, "cursor")
            .addStatement("if (!cursor.enterObject()) return null")
            .addStatement("return readFields(($T) cursor)", concreteCursor)
            .build();

        classBuilder.addMethod(readFields);
        classBuilder.addMethod(map);

        JavaFile.builder(readerName.packageName(), classBuilder.build())
            .build().writeTo(processingEnv.getFiler());
    }

    /**
     * Adds compile-time FNV-1a hash constants to the reader TypeSpec.
     * These are referenced by name in the switch cases, producing clearer
     * generated code while remaining identical to inline integer literals
     * at the JVM level.
     *
     * Example output:
     *   private static final int __H_id   = -1305444774;
     *   private static final int __H_name = 2987074;
     */
    private void addHashConstantsTo(TypeSpec.Builder classBuilder, List<Property> props) {
        for (Property p : props) {
            int hash = fnv1a(p.jsonName());
            classBuilder.addField(
                FieldSpec.builder(TypeName.INT, hashConstantName(p.jsonName()),
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer("$L", hash)
                    .build()
            );
        }
    }

    private CodeBlock buildReaderBody(TypeElement typeElement, ClassName target, List<Property> props) {
        CodeBlock.Builder cb = CodeBlock.builder();
        ClassName readFeature = ClassName.get("me.flame.uniform.json.features", "JsonReadFeature");

        for (Property p : props) {
            cb.addStatement("$T $L = null", boxIfPrimitive(p.typeName()), p.javaName());
        }

        cb.addStatement("final boolean __strictDupes = __config != null && __config.hasReadFeature($T.STRICT_DUPLICATE_DETECTION)", readFeature);
        for (Property p : props) {
            cb.addStatement("boolean __seen_$L = false", p.javaName());
        }

        cb.beginControlFlow("while (cursor.nextField())");

        boolean useLinearChain = props.size() <= SMALL_OBJECT_THRESHOLD;
        if (useLinearChain) {
            buildLinearReaderDispatch(cb, target, props, readFeature);
        } else {
            buildHashSwitchReaderDispatch(cb, target, props, readFeature);
        }

        cb.endControlFlow(); // while

        if (typeElement.getKind() == ElementKind.RECORD) {
            callConstructor(target, props, cb);
        } else {
            List<Property> assignable = props.stream()
                .filter(p -> p.accessKind() != AccessKind.GETTER)
                .toList();
            ExecutableElement fullArgCtor = findFullArgConstructor(typeElement, assignable);
            if (fullArgCtor != null) {
                callConstructor(target, assignable, cb);
            } else {
                boolean hasNoArgCtor = hasNoArgConstructor(typeElement);
                if (!hasNoArgCtor) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "'" + typeElement.getSimpleName() + "' has no full-arg or no-arg constructor.",
                        typeElement);
                }
                cb.addStatement("$T __obj = new $T()", target, target);
                for (Property p : assignable) {
                    switch (p.accessKind()) {
                        case FIELD  -> cb.addStatement("__obj.$L = $L",  p.accessor(), p.javaName());
                        case SETTER -> cb.addStatement("__obj.$L($L)",   p.accessor(), p.javaName());
                        default     -> {}
                    }
                }
                cb.addStatement("return __obj");
            }
        }

        return cb.build();
    }

    private void buildLinearReaderDispatch(CodeBlock.Builder cb, ClassName target,
                                           List<Property> props, ClassName readFeature) {
        boolean first = true;
        for (Property p : props) {
            if (first) {
                cb.beginControlFlow("if (cursor.fieldNameEquals($S))", p.jsonName());
                first = false;
            } else {
                cb.nextControlFlow("else if (cursor.fieldNameEquals($S))", p.jsonName());
            }
            emitDupeCheck(cb, p);
            addReadValue(cb, target, p, "cursor");
        }

        if (!props.isEmpty()) {
            cb.nextControlFlow("else");
            // Unknown field: skip its value to keep pos consistent.
            // The structural bitmask makes this O(n/64) word ops for nested values.
            cb.addStatement("cursor.skipFieldValue()");
            cb.endControlFlow();
        }
    }

    private void buildHashSwitchReaderDispatch(CodeBlock.Builder cb, ClassName target,
                                               List<Property> props, ClassName readFeature) {
        cb.addStatement("int __h = cursor.fieldNameHash()");
        cb.beginControlFlow("switch (__h)");

        for (Property p : props) {
            // Use the named constant rather than an inline integer literal.
            String hashConst = hashConstantName(p.jsonName());
            cb.beginControlFlow("case $L:", hashConst);
            cb.beginControlFlow("if (cursor.fieldNameEquals($S))", p.jsonName());
            emitDupeCheck(cb, p);
            addReadValue(cb, target, p, "cursor");
            cb.endControlFlow();
            cb.addStatement("break");
            cb.endControlFlow();
        }

        cb.beginControlFlow("default:");
        cb.addStatement("cursor.skipFieldValue()");
        cb.addStatement("break");
        cb.endControlFlow();

        cb.endControlFlow(); // switch
    }

    private static void emitDupeCheck(CodeBlock.Builder cb, Property p) {
        cb.beginControlFlow("if (__strictDupes && __seen_$L)", p.javaName());
        cb.addStatement("throw new me.flame.uniform.json.exceptions.JsonException(\"Duplicate field '$L' detected\")", p.jsonName());
        cb.endControlFlow();
        cb.addStatement("__seen_$L = true", p.javaName());
    }

    private static void callConstructor(ClassName target, List<Property> props, CodeBlock.Builder cb) {
        cb.add("return new $T(", target);
        for (int i = 0; i < props.size(); i++) {
            if (i > 0) cb.add(", ");
            cb.add("$L", props.get(i).javaName());
        }
        cb.addStatement(")");
    }

    private static boolean isAsciiNoEscape(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F || c == '"' || c == '\\' || c <= 0x1F) return false;
        }
        return true;
    }

    private void addReadValue(CodeBlock.Builder cb, ClassName ownerType, Property p,
                              String cursorExpr) {
        TypeName t   = p.typeName();
        String   var = p.javaName();

        ClassName jsonCursor         = ClassName.get("me.flame.uniform.json.parser", "JsonReadCursor");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");
        ClassName jsonMapper         = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");
        ClassName resolverRegistry   = ClassName.get("me.flame.uniform.core.resolvers", "ResolverRegistry");
        ClassName simpleCtx          = ClassName.get("me.flame.uniform.core.resolvers", "SimpleResolutionContext");
        ClassName typeResolver       = ClassName.get("me.flame.uniform.core.resolvers", "TypeResolver");
        ClassName dynamicSupplier    = ClassName.get("me.flame.uniform.core.resolvers", "ContextDynamicTypeSupplier");

        CollectionKind ck = collectionKind(p.typeMirror());

        if (ck == CollectionKind.LIST || ck == CollectionKind.SET || ck == CollectionKind.QUEUE) {
            TypeMirror argMirror = ((DeclaredType) p.typeMirror()).getTypeArguments().get(0);
            TypeName   argType   = TypeName.get(argMirror);

            ClassName implClass = switch (ck) {
                case SET   -> ClassName.get("java.util", "LinkedHashSet");
                case QUEUE -> ClassName.get("java.util", "ArrayDeque");
                default    -> ClassName.get("java.util", "ArrayList");
            };
            TypeName ifaceType = switch (ck) {
                case SET   -> ParameterizedTypeName.get(ClassName.get("java.util", "Set"),   argType.box());
                case QUEUE -> ParameterizedTypeName.get(ClassName.get("java.util", "Queue"), argType.box());
                default    -> ParameterizedTypeName.get(ClassName.get("java.util", "List"),  argType.box());
            };

            // enterArrayValue() moves pos to after '[' in-place.
            // If the value is null/absent it returns false.
            cb.addStatement("if (!$L.enterArrayValue()) $L = null", cursorExpr, var);
            cb.beginControlFlow("else");
            cb.addStatement("$T __col = new $T<>()", ifaceType, implClass);
            cb.beginControlFlow("while ($L.nextElement())", cursorExpr);
            emitElementRead(cb, argType, jsonMapper, jsonMapperRegistry, "__col", cursorExpr);
            cb.endControlFlow();
            cb.addStatement("$L.finishFieldAfterValue()", cursorExpr);
            cb.addStatement("$L = __col", var);
            cb.endControlFlow();
            return;
        }

        if (ck == CollectionKind.ARRAY) {
            javax.lang.model.type.ArrayType at = (javax.lang.model.type.ArrayType) p.typeMirror();
            TypeMirror compMirror = at.getComponentType();
            TypeName   compType   = TypeName.get(compMirror);

            ClassName arrayList  = ClassName.get("java.util", "ArrayList");
            TypeName  listOfComp = ParameterizedTypeName.get(ClassName.get("java.util", "List"), compType.box());

            cb.addStatement("if (!$L.enterArrayValue()) $L = null", cursorExpr, var);
            cb.beginControlFlow("else");
            cb.addStatement("$T __tmpList = new $T<>()", listOfComp, arrayList);
            cb.beginControlFlow("while ($L.nextElement())", cursorExpr);
            emitElementRead(cb, compType, jsonMapper, jsonMapperRegistry, "__tmpList", cursorExpr);
            cb.endControlFlow();
            cb.addStatement("$L.finishFieldAfterValue()", cursorExpr);

            if (compType.isPrimitive()) {
                cb.addStatement("$T[] __prim = new $T[__tmpList.size()]", compType, compType);
                cb.beginControlFlow("for (int __i = 0; __i < __tmpList.size(); __i++)");
                cb.addStatement("__prim[__i] = __tmpList.get(__i)");
                cb.endControlFlow();
                cb.addStatement("$L = __prim", var);
            } else {
                cb.addStatement("$T[] __arr2 = new $T[__tmpList.size()]", compType, compType);
                cb.beginControlFlow("for (int __i = 0; __i < __tmpList.size(); __i++)");
                cb.addStatement("__arr2[__i] = __tmpList.get(__i)");
                cb.endControlFlow();
                cb.addStatement("$L = __arr2", var);
            }
            cb.endControlFlow();
            return;
        }

        if (ck == CollectionKind.MAP) {
            List<? extends TypeMirror> args   = ((DeclaredType) p.typeMirror()).getTypeArguments();
            TypeName                   keyType = TypeName.get(args.get(0));
            TypeName                   valType = TypeName.get(args.get(1));

            if (!keyType.equals(ClassName.get(String.class))) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Map field '" + p.javaName() + "': only Map<String, V> is supported.", null);
                return;
            }

            ClassName linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
            TypeName  mapType = ParameterizedTypeName.get(ClassName.get("java.util", "Map"),
                keyType, valType.box());

            // Maps need a sub-cursor because the key/value iteration model is different
            // (nextField gives us a key as bytes, not an element). Keep sub-cursor here.
            ClassName concreteCursorForMap = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
            cb.addStatement("$T __mapCur = ($T) $L.fieldValueCursor()", concreteCursorForMap, concreteCursorForMap, cursorExpr);
            cb.addStatement("if (!__mapCur.enterObject()) $L = null", var);
            cb.beginControlFlow("else");
            cb.addStatement("$T __map = new $T<>()", mapType, linkedHashMap);
            cb.beginControlFlow("while (__mapCur.nextField())");
            cb.addStatement("String __key = __mapCur.fieldNameAsString()");
            emitMapValueRead(cb, valType, jsonMapper, jsonMapperRegistry);
            cb.endControlFlow();
            cb.addStatement("$L = __map", var);
            cb.endControlFlow();
            return;
        }

        // fieldValueParseInt() = scan from fieldValueStart, parse digits, advance pos, consume comma.
        if (t.equals(TypeName.INT)     || t.equals(TypeName.INT.box()))     { cb.addStatement("$L = $L.fieldValueParseInt()",     var, cursorExpr); return; }
        if (t.equals(TypeName.LONG)    || t.equals(TypeName.LONG.box()))    { cb.addStatement("$L = $L.fieldValueParseLong()",    var, cursorExpr); return; }
        if (t.equals(TypeName.DOUBLE)  || t.equals(TypeName.DOUBLE.box()))  { cb.addStatement("$L = $L.fieldValueParseDouble()",  var, cursorExpr); return; }
        if (t.equals(TypeName.FLOAT)   || t.equals(TypeName.FLOAT.box()))   { cb.addStatement("$L = $L.fieldValueParseFloat()",   var, cursorExpr); return; }
        if (t.equals(TypeName.SHORT)   || t.equals(TypeName.SHORT.box()))   { cb.addStatement("$L = (short) $L.fieldValueParseInt()",  var, cursorExpr); return; }
        if (t.equals(TypeName.BYTE)    || t.equals(TypeName.BYTE.box()))    { cb.addStatement("$L = (byte) $L.fieldValueParseInt()",   var, cursorExpr); return; }
        if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) { cb.addStatement("$L = $L.fieldValueParseBoolean()", var, cursorExpr); return; }

        if (t.equals(ClassName.get(String.class))) {
            cb.addStatement("$L = $L.fieldValueParseString()", var, cursorExpr);
            return;
        }

        if (isCoreResolved(p.typeMirror())) {
            emitCoreRead(cb, t, var, cursorExpr, "fieldValueAsJsonValue");
            return;
        }

        // Foreign mapper calls enterObject() itself, so we must hand off a cursor
        // pointing to the start of the value region. fieldValueCursor() does this.
        if (p.abstractOrInterface()) {
            cb.addStatement("$T __subCursor = $L.fieldValueCursor()", jsonCursor, cursorExpr);
            cb.addStatement("$T __ctx = new $T($T.class, $T.class, $S, null)",
                simpleCtx, simpleCtx, t.box(), ownerType, p.jsonName());
            if (p.resolverFqcn() != null) {
                cb.addStatement("$T __resolver = new $L()", ParameterizedTypeName.get(typeResolver, t.box()), p.resolverFqcn());
                cb.addStatement("Class<?> __impl = __resolver.resolve(__ctx)");
            } else {
                cb.addStatement("$T __supplier = $T.getSupplier($T.class)",
                    ParameterizedTypeName.get(dynamicSupplier, t.box()), resolverRegistry, t.box());
                cb.addStatement("if (__supplier == null) throw new IllegalStateException(\"No context-dynamic supplier for \" + $T.class)", t.box());
                cb.addStatement("Class<?> __impl = __supplier.supply(__ctx)");
            }
            cb.addStatement("$T __mapper = ($T) $T.getReader(__impl)",
                ParameterizedTypeName.get(jsonMapper, ClassName.get(Object.class)),
                ParameterizedTypeName.get(jsonMapper, ClassName.get(Object.class)),
                jsonMapperRegistry);
            cb.addStatement("if (__mapper == null) throw new IllegalStateException(\"No mapper for resolved type \" + __impl)");
            cb.addStatement("$L = ($T) __mapper.map(__subCursor)", var, t.box());
            return;
        }

        // enterObjectValue() moves pos to fieldValueStart and enters the '{' in-place,
        // so no JsonCursor allocation is needed. We then call the generated reader's
        // readFields(cursor) directly, which skips the enterObject() step that map()
        // would redundantly call.
        // finishFieldAfterValue() consumes the trailing comma afterwards.
        if (p.typeMirror().getKind() == TypeKind.DECLARED) {
            ClassName concreteCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
            if (t instanceof ClassName declared) {
                ClassName directReader = readerNameFor(declared);
                cb.beginControlFlow("if (!$L.enterObjectValue())", cursorExpr);
                cb.addStatement("$L = null", var);
                cb.nextControlFlow("else");
                cb.addStatement("$L = (($T) $T.INSTANCE).readFields(($T) $L)",
                    var, directReader, directReader,
                    concreteCursor, cursorExpr);
                cb.addStatement("$L.finishFieldAfterValue()", cursorExpr);
                cb.endControlFlow();
            } else {
                cb.addStatement("$T __subCursor = $L.fieldValueCursor()", jsonCursor, cursorExpr);
                cb.addStatement("$T __mapper = ($T) $T.getReader($T.class)",
                    ParameterizedTypeName.get(jsonMapper, t),
                    ParameterizedTypeName.get(jsonMapper, t),
                    jsonMapperRegistry, t);
                cb.addStatement("if (__mapper == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", t);
                cb.addStatement("$L = __mapper.map(__subCursor)", var);
            }
            return;
        }

        cb.addStatement("$L = new $T(stripQuotes($L.fieldValueParseString()))", var, t, cursorExpr);
    }

    private void emitElementRead(CodeBlock.Builder cb, TypeName elemType,
                                 ClassName jsonMapper, ClassName jsonMapperRegistry,
                                 String collVar, String arrCursorExpr) {
        if (elemType.equals(TypeName.INT)     || elemType.equals(TypeName.INT.box()))     { cb.addStatement("$L.add($L.elementValueParseInt())",     collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.LONG)    || elemType.equals(TypeName.LONG.box()))    { cb.addStatement("$L.add($L.elementValueParseLong())",    collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.DOUBLE)  || elemType.equals(TypeName.DOUBLE.box()))  { cb.addStatement("$L.add($L.elementValueParseDouble())",  collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.FLOAT)   || elemType.equals(TypeName.FLOAT.box()))   { cb.addStatement("$L.add($L.elementValueParseFloat())",   collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.SHORT)   || elemType.equals(TypeName.SHORT.box()))   { cb.addStatement("$L.add((short) $L.elementValueParseInt())",  collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.BYTE)    || elemType.equals(TypeName.BYTE.box()))    { cb.addStatement("$L.add((byte) $L.elementValueParseInt())",   collVar, arrCursorExpr); return; }
        if (elemType.equals(TypeName.BOOLEAN) || elemType.equals(TypeName.BOOLEAN.box())) { cb.addStatement("$L.add($L.elementValueParseBoolean())", collVar, arrCursorExpr); return; }
        if (elemType.equals(ClassName.get(String.class)))                                 { cb.addStatement("$L.add($L.elementValueParseString())",  collVar, arrCursorExpr); return; }

        if (isCoreResolved(elemType)) {
            String jvVar = "__jv_elem";
            cb.addStatement("$T $L = $L.elementValueAsJsonValue()", JSON_VALUE, jvVar, arrCursorExpr);
            cb.addStatement("$L.add(($T) $T.INSTANCE.resolve($T.class).deserialize($L))",
                collVar, elemType.box(), CORE_REGISTRY, elemType.box(), jvVar);
            return;
        }

        ClassName jsonCursor = ClassName.get("me.flame.uniform.json.parser", "JsonReadCursor");
        ClassName concreteCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        if (elemType instanceof ClassName declared) {
            // enterObjectElement() advances pos past '{' in-place for the current element.
            // readFields() then reads the nested object without allocating a sub-cursor.
            ClassName directReader = readerNameFor(declared);
            cb.beginControlFlow("if ($L.enterObjectElement())", arrCursorExpr);
            cb.addStatement("$L.add((($T) $T.INSTANCE).readFields(($T) $L))",
                collVar, directReader, directReader,
                concreteCursor, arrCursorExpr);
            cb.addStatement("$L.finishElementAfterValue()", arrCursorExpr);
            cb.nextControlFlow("else");
            cb.addStatement("$L.add(null)", collVar);
            cb.endControlFlow();
        } else {
            cb.addStatement("$T __em = ($T) $T.getReader($T.class)",
                ParameterizedTypeName.get(jsonMapper, elemType),
                ParameterizedTypeName.get(jsonMapper, elemType),
                jsonMapperRegistry, elemType);
            cb.addStatement("if (__em == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", elemType);
            cb.addStatement("$L.add(($T) __em.map($L.elementValueCursor()))", collVar, elemType.box(), arrCursorExpr);
        }
    }

    private void emitMapValueRead(CodeBlock.Builder cb, TypeName valType,
                                  ClassName jsonMapper, ClassName jsonMapperRegistry) {
        // Map values: use fieldValueParseXxx() on the __mapCur sub-cursor
        if (valType.equals(TypeName.INT)     || valType.equals(TypeName.INT.box()))     { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseInt())");     return; }
        if (valType.equals(TypeName.LONG)    || valType.equals(TypeName.LONG.box()))    { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseLong())");    return; }
        if (valType.equals(TypeName.DOUBLE)  || valType.equals(TypeName.DOUBLE.box()))  { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseDouble())");  return; }
        if (valType.equals(TypeName.FLOAT)   || valType.equals(TypeName.FLOAT.box()))   { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseFloat())");   return; }
        if (valType.equals(TypeName.SHORT)   || valType.equals(TypeName.SHORT.box()))   { cb.addStatement("__map.put(__key, (short) __mapCur.fieldValueParseInt())");  return; }
        if (valType.equals(TypeName.BYTE)    || valType.equals(TypeName.BYTE.box()))    { cb.addStatement("__map.put(__key, (byte) __mapCur.fieldValueParseInt())");   return; }
        if (valType.equals(TypeName.BOOLEAN) || valType.equals(TypeName.BOOLEAN.box())) { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseBoolean())"); return; }
        if (valType.equals(ClassName.get(String.class)))                                { cb.addStatement("__map.put(__key, __mapCur.fieldValueParseString())");  return; }

        if (isCoreResolved(valType)) {
            cb.addStatement("$T __jv_mv = __mapCur.fieldValueAsJsonValue()", JSON_VALUE);
            cb.addStatement("__map.put(__key, ($T) $T.INSTANCE.resolve($T.class).deserialize(__jv_mv))",
                valType.box(), CORE_REGISTRY, valType.box());
            return;
        }

        ClassName jsonCursor = ClassName.get("me.flame.uniform.json.parser", "JsonReadCursor");
        ClassName concreteCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        if (valType instanceof ClassName declared) {
            ClassName directReader = readerNameFor(declared);
            cb.beginControlFlow("if (!__mapCur.enterObjectValue())");
            cb.addStatement("__map.put(__key, null)");
            cb.nextControlFlow("else");
            cb.addStatement("__map.put(__key, (($T) $T.INSTANCE).readFields(($T) __mapCur))",
                directReader, directReader, concreteCursor);
            cb.addStatement("__mapCur.finishFieldAfterValue()");
            cb.endControlFlow();
        } else {
            cb.addStatement("$T __vm = ($T) $T.getReader($T.class)",
                ParameterizedTypeName.get(jsonMapper, valType),
                ParameterizedTypeName.get(jsonMapper, valType),
                jsonMapperRegistry, valType);
            cb.addStatement("if (__vm == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", valType);
            cb.addStatement("__map.put(__key, ($T) __vm.map(__mapCur.fieldValueCursor()))", valType.box());
        }
    }

    private void writeWriterInternal(ClassName target, ClassName writerName,
                                     List<Property> props) throws IOException {
        ClassName jsonWriterMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName jsonStringWriter = ClassName.get("me.flame.uniform.json.writers", "JsonStringWriter");
        ClassName jsonConfig       = ClassName.get("me.flame.uniform.json", "JsonConfig");

        TypeSpec.Builder classBuilder = TypeSpec.classBuilder(writerName)
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "\"io.github.flameyossnowy.uniform.processor.UniformJsonProcessor\"")
                .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(jsonWriterMapper, target))
            .addField(FieldSpec.builder(jsonConfig, "__config",
                Modifier.PRIVATE, Modifier.STATIC, Modifier.VOLATILE).build())
            .addMethod(MethodSpec.methodBuilder("setConfig")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(jsonConfig, "cfg")
                .addStatement("__config = cfg")
                .build())
            .addField(ParameterizedTypeName.get(jsonWriterMapper, target), "INSTANCE",
                Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addStaticBlock(CodeBlock.builder()
                .addStatement("INSTANCE = new $T()", writerName)
                .build())
            .addMethod(MethodSpec.constructorBuilder().addModifiers(Modifier.PRIVATE).build())
            .addMethod(MethodSpec.methodBuilder("stripQuotes")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "s")
                .addStatement("if (s == null) return null")
                .addStatement("int len = s.length()")
                .addStatement("if (len >= 2 && s.charAt(0) == '\"' && s.charAt(len - 1) == '\"') return s.substring(1, len - 1)")
                .addStatement("return s")
                .build());

        addFieldNameConstantsTo(classBuilder, props);
        addCommonFieldBytes(classBuilder);

        MethodSpec writeTo = MethodSpec.methodBuilder("writeTo")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.VOID)
            .addParameter(jsonStringWriter, "out")
            .addParameter(target, "value")
            .addStatement("if (value == null) { out.nullValue(); return; }")
            .addStatement("out.beginObject()")
            .addCode(buildWriterBody(props))
            .addStatement("out.endObject()")
            .build();

        classBuilder.addMethod(writeTo);

        JavaFile.builder(writerName.packageName(), classBuilder.build())
            .build().writeTo(processingEnv.getFiler());
    }

    private void addFieldNameConstantsTo(TypeSpec.Builder classBuilder, List<Property> props) {
        for (Property p : props) {
            if (!isAsciiNoEscape(p.jsonName())) continue;
            classBuilder.addField(
                FieldSpec.builder(byte[].class, fieldConstantName(p.jsonName()),
                        Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
                    .initializer(fieldFragmentInitializer(p.jsonName()))
                    .build()
            );
        }
    }

    private void addCommonFieldBytes(TypeSpec.Builder classBuilder) {
        classBuilder.addField(FieldSpec.builder(byte[].class, FIELD_TRUE,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("{(byte)'t',(byte)'r',(byte)'u',(byte)'e'}").build());
        classBuilder.addField(FieldSpec.builder(byte[].class, FIELD_FALSE,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("{(byte)'f',(byte)'a',(byte)'l',(byte)'s',(byte)'e'}").build());
        classBuilder.addField(FieldSpec.builder(byte[].class, FIELD_NULL,
                Modifier.PRIVATE, Modifier.STATIC, Modifier.FINAL)
            .initializer("{(byte)'n',(byte)'u',(byte)'l',(byte)'l'}").build());
    }

    private CodeBlock buildWriterBody(List<Property> props) {
        CodeBlock.Builder cb = CodeBlock.builder();
        ClassName jsonWriterMapper   = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");
        ClassName writeFeature       = ClassName.get("me.flame.uniform.json.features", "JsonWriteFeature");

        cb.addStatement("final boolean __writeNulls = __config == null || __config.hasWriteFeature($T.WRITE_NULL_MAP_VALUES)", writeFeature);

        for (Property p : props) {
            String access = switch (p.accessKind()) {
                case RECORD_COMPONENT, GETTER -> "value." + p.accessor() + "()";
                case FIELD                    -> "value." + p.accessor();
                case SETTER -> {
                    if (p.readAccessor() != null) yield "value." + p.readAccessor() + "()";
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                        "Property '" + p.javaName() + "' is setter-only and will be omitted from serialization.", null);
                    yield null;
                }
            };
            if (access == null) continue;

            TypeName t           = p.typeName();
            boolean  isPrimitive = t.isPrimitive();
            if (!isPrimitive) cb.beginControlFlow("if (__writeNulls || $L != null)", access);

            String fieldConst = fieldConstantName(p.jsonName());
            if (isAsciiNoEscape(p.jsonName())) {
                cb.addStatement("out.writeRaw($L)", fieldConst);
            } else {
                cb.addStatement("out.name($S)", p.jsonName());
            }

            CollectionKind ck = collectionKind(p.typeMirror());

            if (t.equals(TypeName.INT) || t.equals(TypeName.INT.box())
                || t.equals(TypeName.SHORT) || t.equals(TypeName.SHORT.box())
                || t.equals(TypeName.BYTE)  || t.equals(TypeName.BYTE.box())) {
                cb.addStatement("out.writeInt($L)", access);
            } else if (t.equals(TypeName.LONG) || t.equals(TypeName.LONG.box())) {
                cb.addStatement("out.writeLong($L)", access);
            } else if (t.equals(TypeName.DOUBLE) || t.equals(TypeName.DOUBLE.box())) {
                cb.addStatement("out.writeDouble($L)", access);
            } else if (t.equals(TypeName.FLOAT) || t.equals(TypeName.FLOAT.box())) {
                cb.addStatement("out.writeFloat($L)", access);
            } else if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) {
                if (isPrimitive) {
                    cb.addStatement("out.writeRaw($L ? $L : $L)", access, FIELD_TRUE, FIELD_FALSE);
                } else {
                    cb.addStatement("out.writeRaw($L != null && $L ? $L : $L)", access, access, FIELD_TRUE, FIELD_FALSE);
                }
            } else if (t.equals(ClassName.get(String.class))) {
                cb.addStatement("out.writeQuotedAsciiOrUtf8($L)", access);
            } else if (ck == CollectionKind.LIST || ck == CollectionKind.SET || ck == CollectionKind.QUEUE) {
                TypeMirror argMirror = ((DeclaredType) p.typeMirror()).getTypeArguments().get(0);
                emitIterableWrite(cb, access, TypeName.get(argMirror), jsonWriterMapper, jsonMapperRegistry);
            } else if (ck == CollectionKind.ARRAY) {
                javax.lang.model.type.ArrayType at = (javax.lang.model.type.ArrayType) p.typeMirror();
                emitArrayWrite(cb, access, TypeName.get(at.getComponentType()), jsonWriterMapper, jsonMapperRegistry);
            } else if (ck == CollectionKind.MAP) {
                List<? extends TypeMirror> args = ((DeclaredType) p.typeMirror()).getTypeArguments();
                emitMapWrite(cb, access, TypeName.get(args.get(1)), jsonWriterMapper, jsonMapperRegistry);
            } else if (isCoreResolved(p.typeMirror())) {
                cb.beginControlFlow("if ($L == null)", access);
                cb.addStatement("out.writeRaw($L)", FIELD_NULL);
                cb.nextControlFlow("else");
                emitCoreWrite(cb, t, access, "__jv_" + p.javaName());
                cb.endControlFlow();
            } else if (p.typeMirror().getKind() == TypeKind.DECLARED) {
                cb.beginControlFlow("if ($L == null)", access);
                cb.addStatement("out.writeRaw($L)", FIELD_NULL);
                cb.nextControlFlow("else");
                if (p.abstractOrInterface()) {
                    cb.addStatement("$T __w = ($T) $T.getWriter($L.getClass())",
                        ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                        ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                        jsonMapperRegistry, access);
                    cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for runtime type \" + $L.getClass())", access);
                    cb.addStatement("__w.writeTo(out, $L)", access);
                } else if (t instanceof ClassName declared) {
                    ClassName directWriter = writerNameFor(declared);
                    cb.addStatement("(($T) $T.INSTANCE).writeTo(out, $L)",
                        ParameterizedTypeName.get(jsonWriterMapper, t), directWriter, access);
                } else {
                    cb.addStatement("$T __w = ($T) $T.getWriter($T.class)",
                        ParameterizedTypeName.get(jsonWriterMapper, t),
                        ParameterizedTypeName.get(jsonWriterMapper, t),
                        jsonMapperRegistry, t);
                    cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for \" + $T.class)", t);
                    cb.addStatement("__w.writeTo(out, $L)", access);
                }
                cb.endControlFlow();
            } else {
                cb.addStatement("out.value(String.valueOf($L))", access);
            }

            if (!isPrimitive) cb.endControlFlow();
        }

        return cb.build();
    }

    private void emitElementWrite(CodeBlock.Builder cb, String elemExpr, TypeName elemType,
                                  ClassName jsonWriterMapper, ClassName jsonMapperRegistry,
                                  boolean inArray) {
        String nullValueMethod = inArray ? "arrayNullValue" : "nullValue";

        if (elemType.equals(TypeName.INT)    || elemType.equals(TypeName.INT.box())
            || elemType.equals(TypeName.SHORT) || elemType.equals(TypeName.SHORT.box())
            || elemType.equals(TypeName.BYTE)  || elemType.equals(TypeName.BYTE.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.writeInt(($T) $L)", TypeName.INT.box(), elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(TypeName.LONG) || elemType.equals(TypeName.LONG.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.writeLong($L)", elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(TypeName.DOUBLE) || elemType.equals(TypeName.DOUBLE.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.writeDouble($L)", elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(TypeName.FLOAT) || elemType.equals(TypeName.FLOAT.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.writeFloat($L)", elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(TypeName.BOOLEAN) || elemType.equals(TypeName.BOOLEAN.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.writeRaw($L ? $L : $L)", elemExpr, FIELD_TRUE, FIELD_FALSE);
            cb.endControlFlow();
        } else if (elemType.equals(ClassName.get(String.class))) {
            cb.addStatement("out.writeQuotedAsciiOrUtf8((String) $L)", elemExpr);
        } else if (isCoreResolved(elemType)) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            emitCoreWrite(cb, elemType, elemExpr, "__jv_el");
            cb.endControlFlow();
        } else if (elemType instanceof ClassName declared) {
            ClassName directWriter = writerNameFor(declared);
            cb.addStatement("(($T) $T.INSTANCE).writeTo(out, $L)",
                ParameterizedTypeName.get(jsonWriterMapper, elemType), directWriter, elemExpr);
        } else {
            cb.addStatement("$T __ew = ($T) $T.getWriter($L.getClass())",
                ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                jsonMapperRegistry, elemExpr);
            cb.addStatement("if (__ew == null) throw new IllegalStateException(\"No writer for element\")");
            cb.addStatement("__ew.writeTo(out, $L)", elemExpr);
        }
    }

    private void emitIterableWrite(CodeBlock.Builder cb, String access, TypeName elemType,
                                   ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.writeRaw($L)", FIELD_NULL);
        cb.nextControlFlow("else");
        cb.addStatement("out.beginArray()");
        cb.beginControlFlow("for ($T __e : $L)", elemType.box(), access);
        emitElementWrite(cb, "__e", elemType, jsonWriterMapper, jsonMapperRegistry, true);
        cb.endControlFlow();
        cb.addStatement("out.endArray()");
        cb.endControlFlow();
    }

    private void emitArrayWrite(CodeBlock.Builder cb, String access, TypeName compType,
                                ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.writeRaw($L)", FIELD_NULL);
        cb.nextControlFlow("else");
        cb.addStatement("out.beginArray()");
        if (compType.isPrimitive()) {
            cb.beginControlFlow("for ($T __e : $L)", compType, access);
            if (compType.equals(TypeName.INT) || compType.equals(TypeName.SHORT) || compType.equals(TypeName.BYTE)) {
                cb.addStatement("out.writeInt(__e)");
            } else if (compType.equals(TypeName.LONG)) {
                cb.addStatement("out.writeLong(__e)");
            } else if (compType.equals(TypeName.DOUBLE)) {
                cb.addStatement("out.writeDouble(__e)");
            } else if (compType.equals(TypeName.FLOAT)) {
                cb.addStatement("out.writeFloat(__e)");
            } else if (compType.equals(TypeName.BOOLEAN)) {
                cb.addStatement("out.writeRaw(__e ? $L : $L)", FIELD_TRUE, FIELD_FALSE);
            } else {
                cb.addStatement("out.arrayValue(__e)");
            }
        } else {
            cb.beginControlFlow("for ($T __e : $L)", compType.box(), access);
            emitElementWrite(cb, "__e", compType, jsonWriterMapper, jsonMapperRegistry, true);
        }
        cb.endControlFlow();
        cb.addStatement("out.endArray()");
        cb.endControlFlow();
    }

    private void emitMapWrite(CodeBlock.Builder cb, String access, TypeName valType,
                              ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        ClassName mapEntry  = ClassName.get("java.util", "Map", "Entry");
        TypeName  entryType = ParameterizedTypeName.get(mapEntry, ClassName.get(String.class), valType.box());

        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.writeRaw($L)", FIELD_NULL);
        cb.nextControlFlow("else");
        cb.addStatement("out.beginObject()");
        cb.beginControlFlow("for ($T __entry : $L.entrySet())", entryType, access);
        cb.addStatement("out.writeQuotedNameAsciiOrUtf8(__entry.getKey())");
        emitElementWrite(cb, "__entry.getValue()", valType, jsonWriterMapper, jsonMapperRegistry, false);
        cb.endControlFlow();
        cb.addStatement("out.endObject()");
        cb.endControlFlow();
    }

    private void writeModule(ClassName target, ClassName readerName, ClassName writerName,
                             ClassName moduleName) throws IOException {
        ClassName jsonMapperModule   = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperModule");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");
        ClassName jsonConfig         = ClassName.get("me.flame.uniform.json", "JsonConfig");

        MethodSpec register = MethodSpec.methodBuilder("register")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(jsonMapperRegistry, "registry")
            .addStatement("registry.registerReaderInstance($T.class, $T.INSTANCE)", target, readerName)
            .addStatement("registry.registerWriterInstance($T.class, $T.INSTANCE)", target, writerName)
            .build();

        MethodSpec registerWithConfig = MethodSpec.methodBuilder("register")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(jsonMapperRegistry, "registry")
            .addParameter(jsonConfig, "config")
            .addStatement("$T.setConfig(config)", readerName)
            .addStatement("$T.setConfig(config)", writerName)
            .addStatement("registry.registerReaderInstance($T.class, $T.INSTANCE)", target, readerName)
            .addStatement("registry.registerWriterInstance($T.class, $T.INSTANCE)", target, writerName)
            .build();

        TypeSpec module = TypeSpec.classBuilder(moduleName)
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "\"io.github.flameyossnowy.uniform.processor.UniformJsonProcessor\"")
                .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(jsonMapperModule)
            .addMethod(register)
            .addMethod(registerWithConfig)
            .build();

        JavaFile.builder(moduleName.packageName(), module).build().writeTo(processingEnv.getFiler());
    }

    private void writeAggregatorModule(List<ClassName> modules) throws IOException {
        ClassName moduleInterface = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperModule");
        ClassName registryType    = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");

        String requestedName = processingEnv.getOptions().get("uniform.generatedModule");
        String moduleSimpleName = (requestedName == null || requestedName.isBlank())
            ? "UniformGeneratedJsonModule" : requestedName.trim();
        ClassName aggregatorName = ClassName.get("me.flame.uniform.generated", moduleSimpleName);

        MethodSpec.Builder register = MethodSpec.methodBuilder("register")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(registryType, "registry");
        for (ClassName module : modules) {
            register.addStatement("new $T().register(registry)", module);
        }

        TypeSpec aggregator = TypeSpec.classBuilder(aggregatorName)
            .addAnnotation(AnnotationSpec.builder(Generated.class)
                .addMember("value", "\"io.github.flameyossnowy.uniform.processor.UniformJsonProcessor\"")
                .build())
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(moduleInterface)
            .addMethod(register.build())
            .build();

        JavaFile.builder(aggregatorName.packageName(), aggregator).build().writeTo(processingEnv.getFiler());

        String servicesFile = "META-INF/services/me.flame.uniform.json.mappers.JsonMapperModule";
        try {
            try (var out = processingEnv.getFiler()
                .createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", servicesFile)
                .openWriter()) {
                out.write(aggregatorName.canonicalName());
                out.write("\n");
            }
        } catch (FilerException ignored) {}
    }

    private static TypeName boxIfPrimitive(TypeName type) {
        return type.isPrimitive() ? type.box() : type;
    }

    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) {
                byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
                for (byte b : bytes) { h ^= (b & 0xFF); h *= 0x01000193; }
                return h;
            }
            h ^= (c & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }

    private CollectionKind collectionKind(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.ARRAY) return CollectionKind.ARRAY;
        if (mirror.getKind() != TypeKind.DECLARED) return CollectionKind.NONE;
        DeclaredType dt = (DeclaredType) mirror;
        if (!(dt.asElement() instanceof TypeElement te)) return CollectionKind.NONE;
        if (dt.getTypeArguments().isEmpty()) return CollectionKind.NONE;
        String fqcn = elements.getBinaryName(te).toString();
        return switch (fqcn) {
            case "java.util.List",  "java.util.ArrayList"                                               -> CollectionKind.LIST;
            case "java.util.Set",   "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet" -> CollectionKind.SET;
            case "java.util.Queue", "java.util.Deque",   "java.util.ArrayDeque",    "java.util.LinkedList" -> CollectionKind.QUEUE;
            case "java.util.Map",   "java.util.HashMap", "java.util.LinkedHashMap",  "java.util.TreeMap"   -> CollectionKind.MAP;
            default -> CollectionKind.NONE;
        };
    }

    private ExecutableElement findFullArgConstructor(TypeElement type, List<Property> assignable) {
        if (assignable.isEmpty()) return null;
        javax.lang.model.util.Types typeUtils = processingEnv.getTypeUtils();
        outer:
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) e;
            List<? extends VariableElement> params = ctor.getParameters();
            if (params.size() != assignable.size()) continue;
            for (int i = 0; i < params.size(); i++) {
                if (!typeUtils.isSameType(typeUtils.erasure(params.get(i).asType()),
                    typeUtils.erasure(assignable.get(i).typeMirror()))) continue outer;
            }
            return ctor;
        }
        return null;
    }

    private static boolean hasNoArgConstructor(TypeElement type) {
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) e;
            if (!ctor.getParameters().isEmpty()) continue;
            if (ctor.getModifiers().contains(Modifier.PRIVATE)) continue;
            return true;
        }
        return false;
    }
}