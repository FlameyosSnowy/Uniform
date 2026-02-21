package me.flame.uniform.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import me.flame.uniform.core.annotations.ContextDynamicSupplier;
import me.flame.uniform.core.annotations.IgnoreSerializedField;
import me.flame.uniform.core.annotations.Resolves;
import me.flame.uniform.core.annotations.SerializedCreator;
import me.flame.uniform.core.annotations.SerializedField;
import me.flame.uniform.core.annotations.SerializedName;
import me.flame.uniform.core.annotations.SerializedObject;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.FilerException;
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
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.io.IOException;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@AutoService(Processor.class)
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class UniformJsonProcessor extends AbstractProcessor {

    private Elements elements;
    private Types types;

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.elements = processingEnv.getElementUtils();
        this.types = processingEnv.getTypeUtils();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        Set<String> supported = new HashSet<>();
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

        List<ClassName> generatedModules = new ArrayList<>();

        Set<String> visited = new HashSet<>();
        Deque<TypeElement> queue = new ArrayDeque<>();

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

        // Write one aggregated module per compilation unit
        try {
            writeAggregatorModule(generatedModules);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        return true;
    }

    private Map<String, String> collectDynamicSuppliers(RoundEnvironment roundEnv) {
        Map<String, String> suppliers = new HashMap<>();

        for (Element element : roundEnv.getElementsAnnotatedWith(ContextDynamicSupplier.class)) {
            if (element.getKind() != ElementKind.CLASS) continue;
            TypeElement supplierType = (TypeElement) element;
            ContextDynamicSupplier annotation = supplierType.getAnnotation(ContextDynamicSupplier.class);
            Objects.requireNonNull(annotation);

            TypeMirror declared = getClassValueMirror(annotation);
            if (declared == null) continue;
            if (declared.getKind() != TypeKind.DECLARED) continue;
            TypeElement declaredType = (TypeElement) ((DeclaredType) declared).asElement();
            suppliers.put(elements.getBinaryName(declaredType).toString(), elements.getBinaryName(supplierType).toString());
        }

        return suppliers;
    }

    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static TypeMirror getClassValueMirror(ContextDynamicSupplier ann) {
        try {
            ann.value();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }
    @SuppressWarnings("ResultOfMethodCallIgnored")
    private static TypeMirror getClassValueMirror(Resolves ann) {
        try {
            ann.value();
            return null;
        } catch (MirroredTypeException e) {
            return e.getTypeMirror();
        }
    }

    private record Property(String javaName, String jsonName, TypeName typeName, TypeMirror typeMirror, AccessKind accessKind, String accessor, boolean abstractOrInterface, String resolverFqcn) {
    }

    private enum AccessKind {
        FIELD,
        RECORD_COMPONENT,
        GETTER
    }

    private record GeneratedType(ClassName moduleClass) {
    }

    private GeneratedType generateFor(TypeElement type, Map<String, String> dynamicSuppliers, Deque<TypeElement> enqueue) throws IOException {
        ClassName target = ClassName.get(type);
        String pkg = target.packageName();
        String simple = target.simpleName();

        List<Property> properties = collectProperties(type, dynamicSuppliers, enqueue);

        ClassName readerName = ClassName.get(pkg + ".generated", simple + "_JsonReader");
        ClassName writerName = ClassName.get(pkg + ".generated", simple + "_JsonWriter");
        ClassName moduleName = ClassName.get(pkg + ".generated", simple + "_JsonModule");

        writeReader(type, target, readerName, properties);
        writeWriter(type, target, writerName, properties);
        writeModule(target, readerName, writerName, moduleName);

        return new GeneratedType(moduleName);
    }

    private List<Property> collectProperties(TypeElement type, Map<String, String> dynamicSuppliers, Deque<TypeElement> enqueue) {
        List<Property> props = new ArrayList<>();

        if (type.getKind() == ElementKind.RECORD) {
            for (Element e : type.getEnclosedElements()) {
                if (e.getKind() != ElementKind.RECORD_COMPONENT) continue;
                RecordComponentElement rc = (RecordComponentElement) e;
                if (rc.getAnnotation(IgnoreSerializedField.class) != null) continue;

                String javaName = rc.getSimpleName().toString();
                String jsonName = jsonNameFor(rc, javaName);
                props.add(buildProperty(type, rc, javaName, jsonName, rc.asType(), AccessKind.RECORD_COMPONENT, javaName, dynamicSuppliers, enqueue));
            }
            return props;
        }

        // Fields by default
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            VariableElement f = (VariableElement) e;

            if (f.getModifiers().contains(Modifier.STATIC)) continue;
            if (f.getAnnotation(IgnoreSerializedField.class) != null) continue;

            String javaName = f.getSimpleName().toString();
            String jsonName = jsonNameFor(f, javaName);
            props.add(buildProperty(type, f, javaName, jsonName, f.asType(), AccessKind.FIELD, javaName, dynamicSuppliers, enqueue));
        }

        // Add explicit @SerializedField on methods (getters)
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) continue;
            ExecutableElement m = (ExecutableElement) e;
            if (!m.getParameters().isEmpty()) continue;
            if (m.getReturnType().getKind() == TypeKind.VOID) continue;

            SerializedField sf = m.getAnnotation(SerializedField.class);
            SerializedName sn = m.getAnnotation(SerializedName.class);
            if (sf == null && sn == null) continue;
            if (m.getAnnotation(IgnoreSerializedField.class) != null) continue;

            String javaName = m.getSimpleName().toString();
            String jsonName = jsonNameFor(m, javaName);

            props.add(buildProperty(type, m, javaName, jsonName, m.getReturnType(), AccessKind.GETTER, javaName, dynamicSuppliers, enqueue));
        }

        return props;
    }

    private Property buildProperty(TypeElement owner,
                                   Element annotatedElement,
                                   String javaName,
                                   String jsonName,
                                   TypeMirror typeMirror,
                                   AccessKind accessKind,
                                   String accessor,
                                   Map<String, String> dynamicSuppliers,
                                   Deque<TypeElement> enqueue) {

        TypeName typeName = TypeName.get(typeMirror);

        boolean absOrIface = false;
        String resolverFqcn = null;

        if (typeMirror.getKind() == TypeKind.DECLARED) {
            Element te = ((DeclaredType) typeMirror).asElement();
            if (te instanceof TypeElement typeElement) {
                absOrIface = typeElement.getKind() == ElementKind.INTERFACE || typeElement.getModifiers().contains(Modifier.ABSTRACT);

                Resolves resolves = annotatedElement.getAnnotation(Resolves.class);
                if (resolves != null) {
                    TypeMirror mirror = getClassValueMirror(resolves);
                    if (mirror != null && mirror.getKind() == TypeKind.DECLARED) {
                        resolverFqcn = elements.getBinaryName((TypeElement) ((DeclaredType) mirror).asElement()).toString();
                    }
                }

                if (!absOrIface) {
                    // recursive generation: treat referenced concrete types as codegennable
                    if (shouldEnqueueForCodegen(typeElement)) {
                        enqueue.add(typeElement);
                    }
                } else {
                    // Enforce supplier/resolve exists for abstract/interface
                    String declaredFqcn = elements.getBinaryName(typeElement).toString();
                    boolean hasDynamic = dynamicSuppliers.containsKey(declaredFqcn);
                    if (resolverFqcn == null && !hasDynamic) {
                        processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Interface/abstract property '" + owner.getSimpleName() + "." + javaName + "' of type '" + declaredFqcn + "' requires @Resolves or a @ContextDynamicSupplier for that declared type.",
                            annotatedElement
                        );
                    }
                }
            }
        }

        return new Property(javaName, jsonName, typeName, typeMirror, accessKind, accessor, absOrIface, resolverFqcn);
    }

    private boolean shouldEnqueueForCodegen(TypeElement type) {
        String qn = elements.getBinaryName(type).toString();
        if (qn.startsWith("java.")) return false;
        if (qn.startsWith("javax.")) return false;
        if (qn.startsWith("jdk.")) return false;
        if (qn.startsWith("sun.")) return false;
        if (qn.startsWith("org.jetbrains.")) return false;

        // allow records/classes only
        return type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD;
    }

    private String jsonNameFor(Element element, String fallback) {
        SerializedName sn = element.getAnnotation(SerializedName.class);
        if (sn != null && !sn.value().isBlank()) return sn.value();

        SerializedField sf = element.getAnnotation(SerializedField.class);
        if (sf != null && !sf.value().isBlank()) return sf.value();

        return fallback;
    }

    private void writeReader(TypeElement typeElement, ClassName target, ClassName readerName, List<Property> props) throws IOException {
        ClassName jsonCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        ClassName jsonMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");

        MethodSpec ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        MethodSpec map = MethodSpec.methodBuilder("map")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(target)
            .addParameter(jsonCursor, "cursor")
            .addStatement("if (!cursor.enterObject()) return null")
            .addCode(buildReaderBody(typeElement, target, props))
            .build();

        TypeSpec reader = TypeSpec.classBuilder(readerName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(jsonMapper, target))
            .addField(ParameterizedTypeName.get(jsonMapper, target), "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addStaticBlock(com.palantir.javapoet.CodeBlock.builder()
                .addStatement("INSTANCE = new $T()", readerName)
                .build())
            .addMethod(ctor)
            .addMethod(map)
            .addMethod(MethodSpec.methodBuilder("stripQuotes")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "s")
                .addStatement("if (s == null) return null")
                .addStatement("int len = s.length()")
                .addStatement("if (len >= 2 && s.charAt(0) == '\"' && s.charAt(len - 1) == '\"') return s.substring(1, len - 1)")
                .addStatement("return s")
                .build())
            .build();

        JavaFile.builder(readerName.packageName(), reader).build().writeTo(processingEnv.getFiler());
    }

    private com.palantir.javapoet.CodeBlock buildReaderBody(TypeElement typeElement, ClassName target, List<Property> props) {
        com.palantir.javapoet.CodeBlock.Builder cb = com.palantir.javapoet.CodeBlock.builder();

        for (Property p : props) {
            cb.addStatement("$T $L = null", boxIfPrimitive(p.typeName), p.javaName);
        }

        cb.beginControlFlow("while (cursor.nextField())");

        cb.addStatement("int __h = cursor.fieldNameHash()");
        cb.beginControlFlow("switch (__h)");

        for (Property p : props) {
            int hash = fnv1a(p.jsonName);
            cb.beginControlFlow("case $L:", hash);
            cb.beginControlFlow("if (cursor.fieldNameEquals($S))", p.jsonName);
            addReadValue(cb, target, p);
            cb.endControlFlow();
            cb.addStatement("break");
            cb.endControlFlow();
        }

        cb.beginControlFlow("default:");
        cb.addStatement("break");
        cb.endControlFlow();

        cb.endControlFlow();

        cb.endControlFlow();

        // Construct
        if (typeElement.getKind() == ElementKind.RECORD) {
            cb.add("return new $T(", target);
            for (int i = 0; i < props.size(); i++) {
                if (i > 0) cb.add(", ");
                cb.add("$L", props.get(i).javaName);
            }
            cb.addStatement(")");
        } else {
            cb.addStatement("$T __obj = new $T()", target, target);
            for (Property p : props) {
                if (p.accessKind == AccessKind.FIELD) {
                    cb.addStatement("__obj.$L = $L", p.accessor, unboxIfPrimitive(p.typeName, p.javaName));
                }
            }
            cb.addStatement("return __obj");
        }

        return cb.build();
    }

    private static boolean isAsciiNoEscape(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7F) return false;
            if (c == '"' || c == '\\') return false;
            if (c <= 0x1F) return false;
        }
        return true;
    }

    private void addReadValue(com.palantir.javapoet.CodeBlock.Builder cb, ClassName ownerType, Property p) {
        TypeName t = p.typeName;
        String var = p.javaName;

        ClassName jsonCursor = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");
        ClassName jsonMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");
        ClassName jsonWriterMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName resolverRegistry = ClassName.get("me.flame.uniform.core.resolvers", "ResolverRegistry");
        ClassName simpleCtx = ClassName.get("me.flame.uniform.core.resolvers", "SimpleResolutionContext");
        ClassName typeResolver = ClassName.get("me.flame.uniform.core.resolvers", "TypeResolver");
        ClassName dynamicSupplier = ClassName.get("me.flame.uniform.core.resolvers", "ContextDynamicTypeSupplier");

        // List<T>
        if (p.typeMirror.getKind() == TypeKind.DECLARED
            && ((DeclaredType) p.typeMirror).asElement() instanceof TypeElement te
            && elements.getBinaryName(te).contentEquals("java.util.List")
            && !((DeclaredType) p.typeMirror).getTypeArguments().isEmpty()) {

            TypeMirror argMirror = ((DeclaredType) p.typeMirror).getTypeArguments().getFirst();
            TypeName argType = TypeName.get(argMirror);
            cb.addStatement("$T __arr = cursor.fieldValueCursor()", jsonCursor);
            cb.addStatement("if (!__arr.enterArray()) { $L = null; return; }", var);
            cb.addStatement("$T __list = new $T<>()", ParameterizedTypeName.get(ClassName.get(java.util.List.class), argType.box()), java.util.ArrayList.class);
            cb.beginControlFlow("while (__arr.nextElement())");
            if (argType.equals(TypeName.INT) || argType.equals(TypeName.INT.box())) {
                cb.addStatement("__list.add(Integer.parseInt(__arr.elementValue().toString()))");
            } else if (argType.equals(TypeName.LONG) || argType.equals(TypeName.LONG.box())) {
                cb.addStatement("__list.add(Long.parseLong(__arr.elementValue().toString()))");
            } else if (argType.equals(TypeName.BOOLEAN) || argType.equals(TypeName.BOOLEAN.box())) {
                cb.addStatement("__list.add(Boolean.parseBoolean(__arr.elementValue().toString()))");
            } else if (argType.equals(ClassName.get(String.class))) {
                cb.addStatement("__list.add(__arr.elementValueAsUnquotedString())");
            } else {
                cb.addStatement("$T __m = ($T) $T.getReader($T.class)",
                    ParameterizedTypeName.get(jsonMapper, argType),
                    ParameterizedTypeName.get(jsonMapper, argType),
                    jsonMapperRegistry,
                    argType);
                cb.addStatement("if (__m == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", argType);
                cb.addStatement("__list.add(($T) __m.map(__arr.elementValueCursor()))", argType.box());
            }
            cb.endControlFlow();
            cb.addStatement("$L = __list", var);
            return;
        }

        if (t.equals(TypeName.INT) || t.equals(TypeName.INT.box())) {
            cb.addStatement("$L = cursor.fieldValueAsInt()", var);
        } else if (t.equals(TypeName.LONG) || t.equals(TypeName.LONG.box())) {
            cb.addStatement("$L = cursor.fieldValueAsLong()", var);
        } else if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) {
            cb.addStatement("$L = cursor.fieldValueAsBoolean()", var);
        } else if (t.equals(ClassName.get(String.class))) {
            cb.addStatement("$L = cursor.fieldValueAsUnquotedString()", var);
        } else if (p.abstractOrInterface) {
            cb.addStatement("$T __subCursor = cursor.fieldValueCursor()", jsonCursor);

            cb.addStatement("$T __ctx = new $T($T.class, $T.class, $S, null)", simpleCtx, simpleCtx, t.box(), ownerType, p.jsonName);

            if (p.resolverFqcn != null) {
                cb.addStatement("$T __resolver = new $L()", ParameterizedTypeName.get(typeResolver, t.box()), p.resolverFqcn);
                cb.addStatement("Class<?> __impl = __resolver.resolve(__ctx)");
            } else {
                cb.addStatement("$T __supplier = $T.getSupplier($T.class)", ParameterizedTypeName.get(dynamicSupplier, t.box()), resolverRegistry, t.box());
                cb.addStatement("if (__supplier == null) throw new IllegalStateException(\"No context-dynamic supplier for \" + $T.class)", t.box());
                cb.addStatement("Class<?> __impl = __supplier.supply(__ctx)");
            }

            cb.addStatement("$T __mapper = ($T) $T.getReader(__impl)",
                ParameterizedTypeName.get(jsonMapper, ClassName.get(Object.class)),
                ParameterizedTypeName.get(jsonMapper, ClassName.get(Object.class)),
                jsonMapperRegistry);
            cb.addStatement("if (__mapper == null) throw new IllegalStateException(\"No mapper for resolved type \" + __impl)");
            cb.addStatement("$L = ($T) __mapper.map(__subCursor)", var, t.box());
        } else if (p.typeMirror.getKind() == TypeKind.DECLARED) {
            cb.addStatement("$T __subCursor = cursor.fieldValueCursor()", jsonCursor);
            if (t instanceof ClassName declared) {
                ClassName directReader = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonReader");
                cb.addStatement("$L = (($T) $T.INSTANCE).map(__subCursor)", var, ParameterizedTypeName.get(jsonMapper, t), directReader);
            } else {
                cb.addStatement("$T __mapper = ($T) $T.getReader($T.class)", ParameterizedTypeName.get(jsonMapper, t), ParameterizedTypeName.get(jsonMapper, t), jsonMapperRegistry, t);
                cb.addStatement("if (__mapper == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", t);
                cb.addStatement("$L = __mapper.map(__subCursor)", var);
            }
        } else {
            cb.addStatement("$L = new $T(stripQuotes(cursor.fieldValue().toString()))", var, t);
        }
    }

    private void writeWriter(TypeElement typeElement, ClassName target, ClassName writerName, List<Property> props) throws IOException {
        ClassName jsonWriterMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName jsonStringWriter = ClassName.get("me.flame.uniform.json.writers", "JsonStringWriter");

        MethodSpec ctor = MethodSpec.constructorBuilder()
            .addModifiers(Modifier.PRIVATE)
            .build();

        MethodSpec writeTo = MethodSpec.methodBuilder("writeTo")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .returns(TypeName.VOID)
            .addParameter(jsonStringWriter, "out")
            .addParameter(target, "value")
            .addStatement("if (value == null) { out.nullValue(); return; }")
            .addStatement("out.beginObject()")
            .addCode(buildWriterBody(typeElement, props))
            .addStatement("out.endObject()")
            .build();

        TypeSpec writer = TypeSpec.classBuilder(writerName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(jsonWriterMapper, target))
            .addField(ParameterizedTypeName.get(jsonWriterMapper, target), "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addStaticBlock(com.palantir.javapoet.CodeBlock.builder()
                .addStatement("INSTANCE = new $T()", writerName)
                .build())
            .addMethod(ctor)
            .addMethod(writeTo)
            .addMethod(MethodSpec.methodBuilder("stripQuotes")
                .addModifiers(Modifier.PRIVATE, Modifier.STATIC)
                .returns(String.class)
                .addParameter(String.class, "s")
                .addStatement("if (s == null) return null")
                .addStatement("int len = s.length()")
                .addStatement("if (len >= 2 && s.charAt(0) == '\"' && s.charAt(len - 1) == '\"') return s.substring(1, len - 1)")
                .addStatement("return s")
                .build())
            .build();

        JavaFile.builder(writerName.packageName(), writer).build().writeTo(processingEnv.getFiler());
    }

    private com.palantir.javapoet.CodeBlock buildWriterBody(TypeElement typeElement, List<Property> props) {
        com.palantir.javapoet.CodeBlock.Builder cb = com.palantir.javapoet.CodeBlock.builder();

        ClassName jsonWriterMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");

        for (Property p : props) {
            String access = switch (p.accessKind) {
                case RECORD_COMPONENT -> "value." + p.accessor + "()";
                case GETTER -> "value." + p.accessor + "()";
                case FIELD -> "value." + p.accessor;
            };

            if (isAsciiNoEscape(p.jsonName)) {
                cb.addStatement("out.nameAscii($S)", p.jsonName);
            } else {
                cb.addStatement("out.name($S)", p.jsonName);
            }

            TypeName t = p.typeName;
            if (t.equals(TypeName.INT) || t.equals(TypeName.LONG) || t.equals(TypeName.DOUBLE) || t.equals(TypeName.FLOAT)
                || t.equals(TypeName.INT.box()) || t.equals(TypeName.LONG.box()) || t.equals(TypeName.DOUBLE.box()) || t.equals(TypeName.FLOAT.box())) {
                cb.addStatement("out.value($L)", access);
            } else if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) {
                cb.addStatement("out.value($L)", access);
            } else if (t.equals(ClassName.get(String.class))) {
                cb.addStatement("out.value($L)", access);
            } else {
                if (p.typeMirror.getKind() == TypeKind.DECLARED
                    && ((DeclaredType) p.typeMirror).asElement() instanceof TypeElement te
                    && elements.getBinaryName(te).contentEquals("java.util.List")
                    && !((DeclaredType) p.typeMirror).getTypeArguments().isEmpty()) {

                    TypeMirror argMirror = ((DeclaredType) p.typeMirror).getTypeArguments().getFirst();
                    TypeName argType = TypeName.get(argMirror);
                    cb.beginControlFlow("if ($L == null)", access);
                    cb.addStatement("out.nullValue()");
                    cb.nextControlFlow("else");
                    cb.addStatement("out.beginArray()");
                    cb.beginControlFlow("for ($T __e : $L)", argType.box(), access);

                    if (argType.equals(TypeName.INT) || argType.equals(TypeName.INT.box())
                        || argType.equals(TypeName.LONG) || argType.equals(TypeName.LONG.box())
                        || argType.equals(TypeName.DOUBLE) || argType.equals(TypeName.DOUBLE.box())
                        || argType.equals(TypeName.FLOAT) || argType.equals(TypeName.FLOAT.box())) {
                        cb.beginControlFlow("if (__e == null)");
                        cb.addStatement("out.arrayNullValue()");
                        cb.nextControlFlow("else");
                        cb.addStatement("out.arrayValue(($T) __e)", Number.class);
                        cb.endControlFlow();
                    } else if (argType.equals(TypeName.BOOLEAN) || argType.equals(TypeName.BOOLEAN.box())) {
                        cb.beginControlFlow("if (__e == null)");
                        cb.addStatement("out.arrayNullValue()");
                        cb.nextControlFlow("else");
                        cb.addStatement("out.arrayValue(__e.booleanValue())");
                        cb.endControlFlow();
                    } else if (argType.equals(ClassName.get(String.class))) {
                        cb.addStatement("out.arrayValue((String) __e)");
                    } else {
                        if (argType instanceof ClassName declared) {
                            ClassName directWriter = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonWriter");
                            cb.addStatement("(($T) $T.INSTANCE).writeTo(out, __e)", ParameterizedTypeName.get(jsonWriterMapper, argType), directWriter);
                        } else {
                            cb.addStatement("$T __w = ($T) $T.getWriter(__e.getClass())",
                                ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                                ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                                jsonMapperRegistry);
                            cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for element\")");
                            cb.addStatement("__w.writeTo(out, __e)");
                        }
                    }

                    cb.endControlFlow();
                    cb.addStatement("out.endArray()");
                    cb.endControlFlow();
                    continue;
                }

                if (p.typeMirror.getKind() == TypeKind.DECLARED) {
                    // For declared (nested POJO) types, write structurally via a writer mapper
                    cb.beginControlFlow("if ($L == null)", access);
                    cb.addStatement("out.nullValue()");
                    cb.nextControlFlow("else");

                    if (p.abstractOrInterface) {
                        cb.addStatement("$T __w = ($T) $T.getWriter($L.getClass())",
                            ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                            ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                            jsonMapperRegistry,
                            access);
                        cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for runtime type \" + $L.getClass())", access);
                        cb.addStatement("__w.writeTo(out, $L)", access);
                    } else if (t instanceof ClassName declared) {
                        ClassName directWriter = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonWriter");
                        cb.addStatement("(($T) $T.INSTANCE).writeTo(out, $L)", ParameterizedTypeName.get(jsonWriterMapper, t), directWriter, access);
                    } else {
                        cb.addStatement("$T __w = ($T) $T.getWriter($T.class)",
                            ParameterizedTypeName.get(jsonWriterMapper, t),
                            ParameterizedTypeName.get(jsonWriterMapper, t),
                            jsonMapperRegistry,
                            t);
                        cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for \" + $T.class)", t);
                        cb.addStatement("__w.writeTo(out, $L)", access);
                    }
                    cb.endControlFlow();
                } else {
                    cb.addStatement("out.value(String.valueOf($L))", access);
                }
            }
        }

        return cb.build();
    }

    private void writeModule(ClassName target, ClassName readerName, ClassName writerName, ClassName moduleName) throws IOException {
        ClassName jsonMapperModule = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperModule");
        ClassName jsonMapperRegistry = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");

        MethodSpec register = MethodSpec.methodBuilder("register")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(jsonMapperRegistry, "registry")
            .addStatement("registry.registerReaderInstance($T.class, $T.INSTANCE)", target, readerName)
            .addStatement("registry.registerWriterInstance($T.class, $T.INSTANCE)", target, writerName)
            .build();

        TypeSpec module = TypeSpec.classBuilder(moduleName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(jsonMapperModule)
            .addMethod(register)
            .build();

        JavaFile.builder(moduleName.packageName(), module).build().writeTo(processingEnv.getFiler());
    }

    private void writeAggregatorModule(List<ClassName> modules) throws IOException {
        ClassName moduleInterface = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperModule");
        ClassName registryType = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");

        String requestedName = processingEnv.getOptions().get("uniform.generatedModule");
        String moduleSimpleName = (requestedName == null || requestedName.isBlank())
            ? "UniformGeneratedJsonModule"
            : requestedName.trim();
        ClassName aggregatorName = ClassName.get("me.flame.uniform.generated", moduleSimpleName);

        MethodSpec.Builder register = MethodSpec.methodBuilder("register")
            .addAnnotation(Override.class)
            .addModifiers(Modifier.PUBLIC)
            .addParameter(registryType, "registry");

        for (ClassName module : modules) {
            register.addStatement("new $T().register(registry)", module);
        }

        TypeSpec aggregator = TypeSpec.classBuilder(aggregatorName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(moduleInterface)
            .addMethod(register.build())
            .build();

        JavaFile.builder(aggregatorName.packageName(), aggregator).build().writeTo(processingEnv.getFiler());

        // ServiceLoader file is handled by auto-service only for Processor, not for JsonMapperModule.
        // We'll generate META-INF/services entry manually.
        String servicesFile = "META-INF/services/me.flame.uniform.json.mappers.JsonMapperModule";
        try {
            try (var out = processingEnv.getFiler().createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", servicesFile).openWriter()) {
                out.write(aggregatorName.canonicalName());
                out.write("\n");
            }
        } catch (FilerException alreadyExists) {
            // If another round (or incremental compilation) already created the services file in this output,
            // don't fail compilation.
        }
    }

    private static TypeName boxIfPrimitive(TypeName type) {
        return type.isPrimitive() ? type.box() : type;
    }

    private static String unboxIfPrimitive(TypeName type, String varName) {
        if (!type.isPrimitive()) return varName;
        if (type.equals(TypeName.INT)) return varName;
        if (type.equals(TypeName.LONG)) return varName;
        if (type.equals(TypeName.BOOLEAN)) return varName;
        if (type.equals(TypeName.DOUBLE)) return varName;
        if (type.equals(TypeName.FLOAT)) return varName;
        return varName;
    }

    private static String stripQuotes(String s) {
        if (s == null) return null;
        int len = s.length();
        if (len >= 2 && s.charAt(0) == '"' && s.charAt(len - 1) == '"') return s.substring(1, len - 1);
        return s;
    }

    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            // for non-ascii, hash the UTF-8 bytes of the string
            if (c > 0x7F) {
                byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                for (byte b : bytes) {
                    h ^= (b & 0xFF);
                    h *= 0x01000193;
                }
                return h;
            }
            h ^= (c & 0xFF);
            h *= 0x01000193;
        }
        return h;
    }
}
