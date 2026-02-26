package me.flame.uniform.processor;

import com.google.auto.service.AutoService;
import com.palantir.javapoet.ClassName;
import com.palantir.javapoet.CodeBlock;
import com.palantir.javapoet.JavaFile;
import com.palantir.javapoet.MethodSpec;
import com.palantir.javapoet.ParameterizedTypeName;
import com.palantir.javapoet.TypeName;
import com.palantir.javapoet.TypeSpec;
import me.flame.uniform.core.CollectionKind;
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
import javax.tools.Diagnostic;
import java.io.IOException;
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
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public final class UniformJsonProcessor extends AbstractProcessor {

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
        GETTER,
        /**
         * The field's deserialized value is applied via a setter method (e.g. {@code setFoo(T)}).
         * During JSON reading the local variable is populated exactly like FIELD access, but the
         * assignment back to the object uses {@code __obj.setFoo(foo)} instead of {@code __obj.foo = foo}.
         */
        SETTER
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
        writeWriter(target, writerName, properties);
        writeModule(target, readerName, writerName, moduleName);

        return new GeneratedType(moduleName);
    }

    private List<Property> collectProperties(TypeElement type, Map<String, String> dynamicSuppliers, Deque<TypeElement> enqueue) {
        List<Property> props = new ArrayList<>(16);

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

        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.FIELD) continue;
            VariableElement f = (VariableElement) e;

            if (f.getModifiers().contains(Modifier.STATIC)) continue;
            if (f.getAnnotation(IgnoreSerializedField.class) != null) continue;

            String javaName = f.getSimpleName().toString();
            String jsonName = jsonNameFor(f, javaName);
            props.add(buildProperty(type, f, javaName, jsonName, f.asType(), AccessKind.FIELD, javaName, dynamicSuppliers, enqueue));
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
                props.add(buildProperty(type, m, javaName, jsonName, m.getReturnType(), AccessKind.GETTER, javaName, dynamicSuppliers, enqueue));
                propByJavaName.put(javaName, props.getLast());
                continue;
            }

            if (m.getParameters().size() != 1) continue;
            if (m.getReturnType().getKind() != TypeKind.VOID) continue;

            TypeMirror paramType = m.getParameters().getFirst().asType();

            SerializedField sf = m.getAnnotation(SerializedField.class);
            SerializedName  sn = m.getAnnotation(SerializedName.class);

            // Derive the logical field name this setter targets:
            //   - setFoo(...)  → "foo"  (bean convention)
            //   - foo(...)     → "foo"  (fluent convention)
            //   - anything else with explicit annotation → use method name as-is
            String targetFieldName = null;
            if (methodName.startsWith("set") && methodName.length() > 3) {
                targetFieldName = Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4);
            } else if (sf != null || sn != null) {
                // Fluent / non-conventional setter that is explicitly annotated
                targetFieldName = methodName;
            }

            if (targetFieldName == null) continue;

            if (sf == null && sn == null && !propByJavaName.containsKey(targetFieldName)) continue;

            Property existing = propByJavaName.get(targetFieldName);

            if (existing != null) {
                String jsonName = (sf != null || sn != null)
                    ? jsonNameFor(m, existing.jsonName())
                    : existing.jsonName();

                Property upgraded = new Property(
                    existing.javaName(),
                    jsonName,
                    existing.typeName(),
                    existing.typeMirror(),
                    AccessKind.SETTER,
                    methodName,
                    existing.abstractOrInterface(),
                    existing.resolverFqcn()
                );
                propByJavaName.put(targetFieldName, upgraded);
            } else {
                String jsonName = jsonNameFor(m, targetFieldName);
                Property newProp = buildProperty(type, m, targetFieldName, jsonName,
                    paramType, AccessKind.SETTER, methodName, dynamicSuppliers, enqueue);
                propByJavaName.put(targetFieldName, newProp);
            }
        }

        props.clear();
        props.addAll(propByJavaName.values());

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

        Property property = new Property(javaName, jsonName, typeName, typeMirror, accessKind, accessor, false, null);
        if (typeMirror.getKind() == TypeKind.ARRAY) {
            return property;
        }

        if (typeMirror.getKind() == TypeKind.DECLARED) {
            Element te = ((DeclaredType) typeMirror).asElement();
            if (te instanceof TypeElement typeElement) {

                // Skip the abstract/interface check entirely for built-in collection types
                // they are handled structurally by collectionKind() in the codegen, not by
                // the resolver/supplier mechanism which is only for user-defined types.
                if (collectionKind(typeMirror) != CollectionKind.NONE) {
                    // Still need to enqueue any concrete POJO type arguments for codegen
                    for (TypeMirror arg : ((DeclaredType) typeMirror).getTypeArguments()) {
                        if (arg.getKind() == TypeKind.DECLARED) {
                            Element argElem = ((DeclaredType) arg).asElement();
                            if (argElem instanceof TypeElement argType && shouldEnqueueForCodegen(argType)) {
                                enqueue.add(argType);
                            }
                        }
                    }
                    return property;
                }

                absOrIface = typeElement.getKind() == ElementKind.INTERFACE
                    || typeElement.getModifiers().contains(Modifier.ABSTRACT);

                Resolves resolves = annotatedElement.getAnnotation(Resolves.class);
                if (resolves != null) {
                    TypeMirror mirror = getClassValueMirror(resolves);
                    if (mirror != null && mirror.getKind() == TypeKind.DECLARED) {
                        resolverFqcn = elements.getBinaryName(
                            (TypeElement) ((DeclaredType) mirror).asElement()).toString();
                    }
                }

                if (!absOrIface) {
                    if (shouldEnqueueForCodegen(typeElement)) {
                        enqueue.add(typeElement);
                    }
                } else {
                    String declaredFqcn = elements.getBinaryName(typeElement).toString();
                    boolean hasDynamic = dynamicSuppliers.containsKey(declaredFqcn);
                    if (resolverFqcn == null && !hasDynamic) {
                        processingEnv.getMessager().printMessage(
                            Diagnostic.Kind.ERROR,
                            "Interface/abstract property '" + owner.getSimpleName() + "." + javaName
                                + "' of type '" + declaredFqcn + "' requires @Resolves or a "
                                + "@ContextDynamicSupplier for that declared type.",
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

        return type.getKind() == ElementKind.CLASS || type.getKind() == ElementKind.RECORD;
    }

    private static String jsonNameFor(Element element, String fallback) {
        SerializedName sn = element.getAnnotation(SerializedName.class);
        if (sn != null && !sn.value().isBlank()) return sn.value();

        SerializedField sf = element.getAnnotation(SerializedField.class);
        if (sf != null && !sf.value().isBlank()) return sf.value();

        return fallback;
    }

    private void writeReader(TypeElement typeElement, ClassName target, ClassName readerName, List<Property> props) throws IOException {
        ClassName jsonCursor      = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        ClassName jsonMapper      = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");
        ClassName jsonConfig      = ClassName.get("me.flame.uniform.json", "JsonConfig");

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

        writeWriterToFile(target, readerName, jsonMapper, jsonConfig, ctor, map);
    }

    private com.palantir.javapoet.CodeBlock buildReaderBody(TypeElement typeElement, ClassName target, List<Property> props) {
        com.palantir.javapoet.CodeBlock.Builder cb = com.palantir.javapoet.CodeBlock.builder();

        ClassName readFeature = ClassName.get("me.flame.uniform.json.features", "JsonReadFeature");

        for (Property p : props) {
            cb.addStatement("$T $L = null", boxIfPrimitive(p.typeName()), p.javaName());
        }

        // STRICT_DUPLICATE_DETECTION: one boolean per field
        cb.addStatement("final boolean __strictDupes = __config != null && __config.hasReadFeature($T.STRICT_DUPLICATE_DETECTION)", readFeature);
        for (Property p : props) {
            cb.addStatement("boolean __seen_$L = false", p.javaName());
        }

        cb.beginControlFlow("while (cursor.nextField())");

        cb.addStatement("int __h = cursor.fieldNameHash()");
        cb.beginControlFlow("switch (__h)");

        for (Property p : props) {
            int hash = fnv1a(p.jsonName());
            cb.beginControlFlow("case $L:", hash);
            cb.beginControlFlow("if (cursor.fieldNameEquals($S))", p.jsonName());

            // STRICT_DUPLICATE_DETECTION check
            cb.beginControlFlow("if (__strictDupes && __seen_$L)", p.javaName());
            cb.addStatement("throw new me.flame.uniform.json.exceptions.JsonException(\"Duplicate field '$L' detected\")", p.jsonName());
            cb.endControlFlow();
            cb.addStatement("__seen_$L = true", p.javaName());

            addReadValue(cb, target, p);
            cb.endControlFlow();
            cb.addStatement("break");
            cb.endControlFlow();
        }

        cb.beginControlFlow("default:");
        cb.beginControlFlow("if (__config == null || !__config.hasReadFeature($T.IGNORE_UNDEFINED))", readFeature);
        cb.endControlFlow();
        cb.addStatement("break");
        cb.endControlFlow();

        cb.endControlFlow(); // switch
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
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "'" + typeElement.getSimpleName() + "' has no full-arg constructor and no "
                            + "no-arg constructor. Deserialization will likely fail at runtime. "
                            + "Add either a constructor matching all serialized fields or a no-arg constructor.",
                        typeElement
                    );
                }

                boolean anySetter = assignable.stream()
                    .anyMatch(p -> p.accessKind() == AccessKind.SETTER);
                boolean anyField = assignable.stream()
                    .anyMatch(p -> p.accessKind() == AccessKind.FIELD);

                if (!anySetter && !anyField) {
                    processingEnv.getMessager().printMessage(
                        Diagnostic.Kind.WARNING,
                        "'" + typeElement.getSimpleName() + "' has no full-arg constructor and no "
                            + "writable properties (all properties are getter-only). "
                            + "Deserialized values will be silently discarded.",
                        typeElement
                    );
                }

                cb.addStatement("$T __obj = new $T()", target, target);
                for (Property p : assignable) {
                    switch (p.accessKind()) {
                        case FIELD -> cb.addStatement("__obj.$L = $L", p.accessor(), p.javaName());
                        case SETTER -> cb.addStatement("__obj.$L($L)", p.accessor(), p.javaName());
                        default -> { /* GETTER filtered out above */ }
                    }
                }
                cb.addStatement("return __obj");
            }
        }

        return cb.build();
    }

    private void callConstructor(ClassName target, List<Property> props, CodeBlock.Builder cb) {
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
            if (c > 0x7F) return false;
            if (c == '"' || c == '\\') return false;
            if (c <= 0x1F) return false;
        }
        return true;
    }

    private void addReadValue(com.palantir.javapoet.CodeBlock.Builder cb, ClassName ownerType, Property p) {
        TypeName t   = p.typeName();
        String   var = p.javaName();

        ClassName jsonCursor          = ClassName.get("me.flame.uniform.json.parser.lowlevel", "JsonCursor");
        ClassName jsonMapperRegistry  = ClassName.get("me.flame.uniform.json.mappers", "JsonMapperRegistry");
        ClassName jsonMapper          = ClassName.get("me.flame.uniform.json.mappers", "JsonMapper");
        ClassName resolverRegistry    = ClassName.get("me.flame.uniform.core.resolvers", "ResolverRegistry");
        ClassName simpleCtx           = ClassName.get("me.flame.uniform.core.resolvers", "SimpleResolutionContext");
        ClassName typeResolver        = ClassName.get("me.flame.uniform.core.resolvers", "TypeResolver");
        ClassName dynamicSupplier     = ClassName.get("me.flame.uniform.core.resolvers", "ContextDynamicTypeSupplier");

        CollectionKind ck = collectionKind(p.typeMirror());

        if (ck == CollectionKind.LIST || ck == CollectionKind.SET || ck == CollectionKind.QUEUE) {
            TypeMirror argMirror = ((DeclaredType) p.typeMirror()).getTypeArguments().getFirst();
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

            cb.addStatement("$T __arr = cursor.fieldValueCursor()", jsonCursor);
            cb.addStatement("if (!__arr.enterArray()) { $L = null; break; }", var);
            cb.addStatement("$T __col = new $T<>()", ifaceType, implClass);
            cb.beginControlFlow("while (__arr.nextElement())");
            emitElementRead(cb, argType, jsonMapper, jsonMapperRegistry, "__col");
            cb.endControlFlow();
            cb.addStatement("$L = __col", var);
            return;
        }

        if (ck == CollectionKind.ARRAY) {
            javax.lang.model.type.ArrayType at = (javax.lang.model.type.ArrayType) p.typeMirror();
            TypeMirror compMirror = at.getComponentType();
            TypeName   compType   = TypeName.get(compMirror);

            ClassName arrayList = ClassName.get("java.util", "ArrayList");
            TypeName  listOfComp = ParameterizedTypeName.get(ClassName.get("java.util", "List"), compType.box());

            cb.addStatement("$T __arr = cursor.fieldValueCursor()", jsonCursor);
            cb.addStatement("if (!__arr.enterArray()) { $L = null; break; }", var);
            cb.addStatement("$T __tmpList = new $T<>()", listOfComp, arrayList);
            cb.beginControlFlow("while (__arr.nextElement())");
            emitElementRead(cb, compType, jsonMapper, jsonMapperRegistry, "__tmpList");
            cb.endControlFlow();

            if (compType.equals(TypeName.INT)) {
                cb.addStatement("$L = __tmpList.stream().mapToInt(Integer::intValue).toArray()", var);
            } else if (compType.equals(TypeName.LONG)) {
                cb.addStatement("$L = __tmpList.stream().mapToLong(Long::longValue).toArray()", var);
            } else if (compType.equals(TypeName.DOUBLE)) {
                cb.addStatement("$L = __tmpList.stream().mapToDouble(Double::doubleValue).toArray()", var);
            } else if (compType.isPrimitive()) {
                cb.addStatement("$T[] $L__raw = new $T[__tmpList.size()]", compType.box(), var, compType.box());
                cb.addStatement("for (int __i = 0; __i < __tmpList.size(); __i++) $L__raw[__i] = __tmpList.get(__i)", var);
                cb.addStatement("$T[] __prim = new $T[__tmpList.size()]", compType, compType);
                cb.beginControlFlow("for (int __i = 0; __i < __tmpList.size(); __i++)");
                cb.addStatement("__prim[__i] = $L__raw[__i]", var);
                cb.endControlFlow();
                cb.addStatement("$L = __prim", var);
            } else {
                cb.addStatement("$L = __tmpList.toArray(new $T[0])", var, compType.box());
            }
            return;
        }

        if (ck == CollectionKind.MAP) {
            List<? extends TypeMirror> args    = ((DeclaredType) p.typeMirror()).getTypeArguments();
            TypeName                   keyType = TypeName.get(args.get(0));
            TypeName                   valType = TypeName.get(args.get(1));

            if (!keyType.equals(ClassName.get(String.class))) {
                processingEnv.getMessager().printMessage(
                    Diagnostic.Kind.ERROR,
                    "Map field '" + p.javaName() + "': only Map<String, V> is supported by Uniform codegen. " +
                        "Non-String keys require a custom JsonMapper.",
                    null
                );
                return;
            }

            ClassName linkedHashMap = ClassName.get("java.util", "LinkedHashMap");
            TypeName  mapType = ParameterizedTypeName.get(ClassName.get("java.util", "Map"), keyType, valType.box());

            cb.addStatement("$T __mapCur = cursor.fieldValueCursor()", jsonCursor);
            cb.addStatement("if (!__mapCur.enterObject()) { $L = null; break; }", var);
            cb.addStatement("$T __map = new $T<>()", mapType, linkedHashMap);
            cb.beginControlFlow("while (__mapCur.nextField())");
            cb.addStatement("String __key = __mapCur.fieldName().toString()");
            emitMapValueRead(cb, valType, jsonMapper, jsonMapperRegistry);
            cb.endControlFlow();
            cb.addStatement("$L = __map", var);
            return;
        }

        if (t.equals(TypeName.INT)     || t.equals(TypeName.INT.box()))     { cb.addStatement("$L = cursor.fieldValueAsInt()",     var); return; }
        if (t.equals(TypeName.LONG)    || t.equals(TypeName.LONG.box()))    { cb.addStatement("$L = cursor.fieldValueAsLong()",    var); return; }
        if (t.equals(TypeName.DOUBLE)  || t.equals(TypeName.DOUBLE.box()))  { cb.addStatement("$L = cursor.fieldValueAsDouble()",  var); return; }
        if (t.equals(TypeName.FLOAT)   || t.equals(TypeName.FLOAT.box()))   { cb.addStatement("$L = cursor.fieldValueAsFloat()",   var); return; }
        if (t.equals(TypeName.SHORT)   || t.equals(TypeName.SHORT.box()))   { cb.addStatement("$L = cursor.fieldValueAsShort()",   var); return; }
        if (t.equals(TypeName.BYTE)    || t.equals(TypeName.BYTE.box()))    { cb.addStatement("$L = cursor.fieldValueAsByte()",    var); return; }
        if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) { cb.addStatement("$L = cursor.fieldValueAsBoolean()", var); return; }
        if (t.equals(ClassName.get(String.class))) { cb.addStatement("$L = cursor.fieldValueAsUnquotedString()", var); return; }

        if (p.abstractOrInterface()) {
            cb.addStatement("$T __subCursor = cursor.fieldValueCursor()", jsonCursor);
            cb.addStatement("$T __ctx = new $T($T.class, $T.class, $S, null)", simpleCtx, simpleCtx, t.box(), ownerType, p.jsonName());
            if (p.resolverFqcn() != null) {
                cb.addStatement("$T __resolver = new $L()", ParameterizedTypeName.get(typeResolver, t.box()), p.resolverFqcn());
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
            return;
        }

        if (p.typeMirror().getKind() == TypeKind.DECLARED) {
            cb.addStatement("$T __subCursor = cursor.fieldValueCursor()", jsonCursor);
            if (t instanceof ClassName declared) {
                ClassName directReader = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonReader");
                cb.addStatement("$L = (($T) $T.INSTANCE).map(__subCursor)", var, ParameterizedTypeName.get(jsonMapper, t), directReader);
            } else {
                cb.addStatement("$T __mapper = ($T) $T.getReader($T.class)", ParameterizedTypeName.get(jsonMapper, t), ParameterizedTypeName.get(jsonMapper, t), jsonMapperRegistry, t);
                cb.addStatement("if (__mapper == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", t);
                cb.addStatement("$L = __mapper.map(__subCursor)", var);
            }
            return;
        }

        cb.addStatement("$L = new $T(stripQuotes(cursor.fieldValue().toString()))", var, t);
    }

    private static void emitElementRead(com.palantir.javapoet.CodeBlock.Builder cb,
                                        TypeName elemType,
                                        ClassName jsonMapper,
                                        ClassName jsonMapperRegistry,
                                        String collVar) {
        if (elemType.equals(TypeName.INT) || elemType.equals(TypeName.INT.box()))
            cb.addStatement("$L.add(__arr.elementValueAsInt())", collVar);
        else if (elemType.equals(TypeName.LONG) || elemType.equals(TypeName.LONG.box()))
            cb.addStatement("$L.add(__arr.elementValueAsLong())", collVar);
        else if (elemType.equals(TypeName.DOUBLE) || elemType.equals(TypeName.DOUBLE.box()))
            cb.addStatement("$L.add(__arr.elementValueAsDouble())", collVar);
        else if (elemType.equals(TypeName.FLOAT) || elemType.equals(TypeName.FLOAT.box()))
            cb.addStatement("$L.add(__arr.elementValueAsFloat())", collVar);
        else if (elemType.equals(TypeName.SHORT) || elemType.equals(TypeName.SHORT.box()))
            cb.addStatement("$L.add(__arr.elementValueAsShort())", collVar);
        else if (elemType.equals(TypeName.BYTE) || elemType.equals(TypeName.BYTE.box()))
            cb.addStatement("$L.add(__arr.elementValueAsByte())", collVar);
        else if (elemType.equals(TypeName.BOOLEAN) || elemType.equals(TypeName.BOOLEAN.box()))
            cb.addStatement("$L.add(__arr.elementValueAsBoolean())", collVar);
        else if (elemType.equals(ClassName.get(String.class)))
            cb.addStatement("$L.add(__arr.elementValueAsUnquotedString())", collVar);
        else if (elemType instanceof ClassName declared) {
            ClassName directReader = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonReader");
            cb.addStatement("$L.add((($T) $T.INSTANCE).map(__arr.elementValueCursor()))",
                collVar,
                ParameterizedTypeName.get(jsonMapper, elemType),
                directReader);
        } else {
            cb.addStatement("$T __em = ($T) $T.getReader($T.class)",
                ParameterizedTypeName.get(jsonMapper, elemType),
                ParameterizedTypeName.get(jsonMapper, elemType),
                jsonMapperRegistry, elemType);
            cb.addStatement("if (__em == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", elemType);
            cb.addStatement("$L.add(($T) __em.map(__arr.elementValueCursor()))", collVar, elemType.box());
        }
    }

    private static void emitMapValueRead(CodeBlock.Builder cb,
                                         TypeName valType,
                                         ClassName jsonMapper,
                                         ClassName jsonMapperRegistry) {
        if (valType.equals(TypeName.INT)     || valType.equals(TypeName.INT.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsInt())", "__map", "__key");
        else if (valType.equals(TypeName.LONG)    || valType.equals(TypeName.LONG.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsLong())", "__map", "__key");
        else if (valType.equals(TypeName.DOUBLE)  || valType.equals(TypeName.DOUBLE.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsDouble())", "__map", "__key");
        else if (valType.equals(TypeName.FLOAT)   || valType.equals(TypeName.FLOAT.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsFloat())", "__map", "__key");
        else if (valType.equals(TypeName.SHORT)   || valType.equals(TypeName.SHORT.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsShort())", "__map", "__key");
        else if (valType.equals(TypeName.BYTE)    || valType.equals(TypeName.BYTE.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsByte())", "__map", "__key");
        else if (valType.equals(TypeName.BOOLEAN) || valType.equals(TypeName.BOOLEAN.box()))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsBoolean())", "__map", "__key");
        else if (valType.equals(ClassName.get(String.class)))
            cb.addStatement("$L.put($L, __mapCur.fieldValueAsUnquotedString())", "__map", "__key");
        else if (valType instanceof ClassName declared) {
            ClassName directReader = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonReader");
            cb.addStatement("$L.put($L, (($T) $T.INSTANCE).map(__mapCur.fieldValueCursor()))",
                "__map", "__key",
                ParameterizedTypeName.get(jsonMapper, valType),
                directReader);
        } else {
            cb.addStatement("$T __vm = ($T) $T.getReader($T.class)",
                ParameterizedTypeName.get(jsonMapper, valType),
                ParameterizedTypeName.get(jsonMapper, valType),
                jsonMapperRegistry, valType);
            cb.addStatement("if (__vm == null) throw new IllegalStateException(\"No mapper for \" + $T.class)", valType);
            cb.addStatement("$L.put($L, ($T) __vm.map(__mapCur.fieldValueCursor()))", "__map", "__key", valType.box());
        }
    }

    private void writeWriter(ClassName target, ClassName writerName, List<Property> props) throws IOException {
        ClassName jsonWriterMapper = ClassName.get("me.flame.uniform.json.mappers", "JsonWriterMapper");
        ClassName jsonStringWriter = ClassName.get("me.flame.uniform.json.writers", "JsonStringWriter");
        ClassName jsonConfig       = ClassName.get("me.flame.uniform.json", "JsonConfig");

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
            .addCode(buildWriterBody(props))
            .addStatement("out.endObject()")
            .build();

        writeWriterToFile(target, writerName, jsonWriterMapper, jsonConfig, ctor, writeTo);
    }

    private void writeWriterToFile(ClassName target, ClassName writerName, ClassName jsonWriterMapper, ClassName jsonConfig, MethodSpec ctor, MethodSpec writeTo) throws IOException {
        TypeSpec writer = TypeSpec.classBuilder(writerName)
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(ParameterizedTypeName.get(jsonWriterMapper, target))
            .addField(com.palantir.javapoet.FieldSpec.builder(jsonConfig, "__config", Modifier.PRIVATE, Modifier.STATIC, Modifier.VOLATILE)
                .build())
            .addMethod(MethodSpec.methodBuilder("setConfig")
                .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
                .addParameter(jsonConfig, "cfg")
                .addStatement("__config = cfg")
                .build())
            .addField(ParameterizedTypeName.get(jsonWriterMapper, target), "INSTANCE", Modifier.PUBLIC, Modifier.STATIC, Modifier.FINAL)
            .addStaticBlock(CodeBlock.builder()
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

    private void writeModule(ClassName target, ClassName readerName, ClassName writerName, ClassName moduleName) throws IOException {
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
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL)
            .addSuperinterface(jsonMapperModule)
            .addMethod(register)
            .addMethod(registerWithConfig)
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

        String servicesFile = "META-INF/services/me.flame.uniform.json.mappers.JsonMapperModule";
        try {
            try (var out = processingEnv.getFiler().createResource(javax.tools.StandardLocation.CLASS_OUTPUT, "", servicesFile).openWriter()) {
                out.write(aggregatorName.canonicalName());
                out.write("\n");
            }
        } catch (FilerException alreadyExists) {
            // incremental compilation - file already written
        }
    }

    private static TypeName boxIfPrimitive(TypeName type) {
        return type.isPrimitive() ? type.box() : type;
    }

    private static int fnv1a(String s) {
        int h = 0x811c9dc5;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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
                case SETTER                   -> "value." + p.javaName();
            };

            TypeName t           = p.typeName();
            boolean  isPrimitive = t.isPrimitive();

            if (!isPrimitive) cb.beginControlFlow("if (__writeNulls || $L != null)", access);

            if (isAsciiNoEscape(p.jsonName())) cb.addStatement("out.nameAscii($S)", p.jsonName());
            else                               cb.addStatement("out.name($S)", p.jsonName());

            CollectionKind ck = collectionKind(p.typeMirror());

            if (isNumericType(t)) {
                cb.addStatement("out.value($L)", access);
            } else if (t.equals(TypeName.BOOLEAN) || t.equals(TypeName.BOOLEAN.box())) {
                cb.addStatement("out.value($L)", access);
            } else if (t.equals(ClassName.get(String.class))) {
                cb.addStatement("out.value($L)", access);
            } else if (ck == CollectionKind.LIST || ck == CollectionKind.SET || ck == CollectionKind.QUEUE) {
                TypeMirror argMirror = ((DeclaredType) p.typeMirror()).getTypeArguments().getFirst();
                TypeName   argType   = TypeName.get(argMirror);
                emitIterableWrite(cb, access, argType, jsonWriterMapper, jsonMapperRegistry);
            } else if (ck == CollectionKind.ARRAY) {
                javax.lang.model.type.ArrayType at = (javax.lang.model.type.ArrayType) p.typeMirror();
                TypeName compType = TypeName.get(at.getComponentType());
                emitArrayWrite(cb, access, compType, jsonWriterMapper, jsonMapperRegistry);
            } else if (ck == CollectionKind.MAP) {
                List<? extends TypeMirror> args   = ((DeclaredType) p.typeMirror()).getTypeArguments();
                TypeName                   valType = TypeName.get(args.get(1));
                emitMapWrite(cb, access, valType, jsonWriterMapper, jsonMapperRegistry);
            } else if (p.typeMirror().getKind() == TypeKind.DECLARED) {
                cb.beginControlFlow("if ($L == null)", access);
                cb.addStatement("out.nullValue()");
                cb.nextControlFlow("else");
                if (p.abstractOrInterface()) {
                    cb.addStatement("$T __w = ($T) $T.getWriter($L.getClass())",
                        ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                        ParameterizedTypeName.get(jsonWriterMapper, ClassName.get(Object.class)),
                        jsonMapperRegistry, access);
                    cb.addStatement("if (__w == null) throw new IllegalStateException(\"No writer for runtime type \" + $L.getClass())", access);
                    cb.addStatement("__w.writeTo(out, $L)", access);
                } else if (t instanceof ClassName declared) {
                    ClassName directWriter = ClassName.get(declared.packageName() + ".generated", declared.simpleName() + "_JsonWriter");
                    cb.addStatement("(($T) $T.INSTANCE).writeTo(out, $L)", ParameterizedTypeName.get(jsonWriterMapper, t), directWriter, access);
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

    private static void emitElementWrite(com.palantir.javapoet.CodeBlock.Builder cb,
                                         String elemExpr, TypeName elemType,
                                         ClassName jsonWriterMapper, ClassName jsonMapperRegistry,
                                         boolean inArray) {
        String valueMethod     = inArray ? "arrayValue"     : "value";
        String nullValueMethod = inArray ? "arrayNullValue" : "nullValue";

        if (isNumericType(elemType)) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.$L(($T) $L)", valueMethod, Number.class, elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(TypeName.BOOLEAN) || elemType.equals(TypeName.BOOLEAN.box())) {
            cb.beginControlFlow("if ($L == null)", elemExpr);
            cb.addStatement("out.$L()", nullValueMethod);
            cb.nextControlFlow("else");
            cb.addStatement("out.$L($L.booleanValue())", valueMethod, elemExpr);
            cb.endControlFlow();
        } else if (elemType.equals(ClassName.get(String.class))) {
            cb.addStatement("out.$L((String) $L)", valueMethod, elemExpr);
        } else if (elemType instanceof ClassName declared) {
            ClassName directWriter = ClassName.get(
                declared.packageName() + ".generated", declared.simpleName() + "_JsonWriter");
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

    private static void emitIterableWrite(com.palantir.javapoet.CodeBlock.Builder cb,
                                          String access, TypeName elemType,
                                          ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.nullValue()");
        cb.nextControlFlow("else");
        cb.addStatement("out.beginArray()");
        cb.beginControlFlow("for ($T __e : $L)", elemType.box(), access);
        emitElementWrite(cb, "__e", elemType, jsonWriterMapper, jsonMapperRegistry, true);
        cb.endControlFlow();
        cb.addStatement("out.endArray()");
        cb.endControlFlow();
    }

    private static void emitArrayWrite(com.palantir.javapoet.CodeBlock.Builder cb,
                                       String access, TypeName compType,
                                       ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.nullValue()");
        cb.nextControlFlow("else");
        cb.addStatement("out.beginArray()");

        if (compType.isPrimitive()) {
            cb.beginControlFlow("for ($T __e : $L)", compType, access);
            cb.addStatement("out.arrayValue(__e)");
        } else {
            cb.beginControlFlow("for ($T __e : $L)", compType.box(), access);
            emitElementWrite(cb, "__e", compType, jsonWriterMapper, jsonMapperRegistry, true);
        }

        cb.endControlFlow();
        cb.addStatement("out.endArray()");
        cb.endControlFlow();
    }

    private static void emitMapWrite(com.palantir.javapoet.CodeBlock.Builder cb,
                                     String access, TypeName valType,
                                     ClassName jsonWriterMapper, ClassName jsonMapperRegistry) {
        ClassName mapEntry = ClassName.get("java.util", "Map", "Entry");
        TypeName entryType = ParameterizedTypeName.get(mapEntry, ClassName.get(String.class), valType.box());

        cb.beginControlFlow("if ($L == null)", access);
        cb.addStatement("out.nullValue()");
        cb.nextControlFlow("else");
        cb.addStatement("out.beginObject()");
        cb.beginControlFlow("for ($T __entry : $L.entrySet())", entryType, access);
        cb.addStatement("out.nameAscii(__entry.getKey())");
        emitElementWrite(cb, "__entry.getValue()", valType, jsonWriterMapper, jsonMapperRegistry, false);
        cb.endControlFlow();
        cb.addStatement("out.endObject()");
        cb.endControlFlow();
    }

    private CollectionKind collectionKind(TypeMirror mirror) {
        if (mirror.getKind() == TypeKind.ARRAY) return CollectionKind.ARRAY;
        if (mirror.getKind() != TypeKind.DECLARED) return CollectionKind.NONE;

        DeclaredType dt = (DeclaredType) mirror;
        if (!(dt.asElement() instanceof TypeElement te)) return CollectionKind.NONE;
        if (dt.getTypeArguments().isEmpty()) return CollectionKind.NONE;

        String fqcn = elements.getBinaryName(te).toString();
        return switch (fqcn) {
            case "java.util.List",
                 "java.util.ArrayList"  -> CollectionKind.LIST;
            case "java.util.Set",
                 "java.util.HashSet",
                 "java.util.LinkedHashSet",
                 "java.util.TreeSet"     -> CollectionKind.SET;
            case "java.util.Queue",
                 "java.util.Deque",
                 "java.util.ArrayDeque",
                 "java.util.LinkedList"  -> CollectionKind.QUEUE;
            case "java.util.Map",
                 "java.util.HashMap",
                 "java.util.LinkedHashMap",
                 "java.util.TreeMap"     -> CollectionKind.MAP;
            default                      -> CollectionKind.NONE;
        };
    }

    private static boolean isNumericType(TypeName t) {
        return t.equals(TypeName.INT)    || t.equals(TypeName.INT.box())
            || t.equals(TypeName.LONG)   || t.equals(TypeName.LONG.box())
            || t.equals(TypeName.DOUBLE) || t.equals(TypeName.DOUBLE.box())
            || t.equals(TypeName.FLOAT)  || t.equals(TypeName.FLOAT.box())
            || t.equals(TypeName.SHORT)  || t.equals(TypeName.SHORT.box())
            || t.equals(TypeName.BYTE)   || t.equals(TypeName.BYTE.box());
    }

    /**
     * Looks for a constructor whose parameter types match the given properties
     * in order. Type erasure is accounted for — only the declared (erased) type
     * of each parameter needs to match the property's erased type.
     */
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
                TypeMirror paramErased    = typeUtils.erasure(params.get(i).asType());
                TypeMirror propertyErased = typeUtils.erasure(assignable.get(i).typeMirror());
                if (!typeUtils.isSameType(paramErased, propertyErased)) continue outer;
            }
            return ctor;
        }
        return null;
    }

    /**
     * Returns true if the type declares an accessible no-arg constructor
     * (public or package-private).
     */
    private static boolean hasNoArgConstructor(TypeElement type) {
        for (Element e : type.getEnclosedElements()) {
            if (e.getKind() != ElementKind.CONSTRUCTOR) continue;
            ExecutableElement ctor = (ExecutableElement) e;
            if (!ctor.getParameters().isEmpty()) continue;
            Set<Modifier> mods = ctor.getModifiers();
            if (mods.contains(Modifier.PRIVATE)) continue;
            return true;
        }
        return false;
    }
}