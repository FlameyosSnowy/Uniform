# Uniform

**High-performance JSON serialization for Java 21, powered by SIMD-accelerated parsing and compile-time code generation.**

Uniform is inspired by Jackson and simdjson but takes a different approach: instead of reflection at runtime, it generates specialized reader/writer classes at compile time via an annotation processor. The result is allocation-free hot paths, zero reflection overhead, and throughput that beats Jackson by up to **3.57×** on writes and **1.9×** on reads.

```java
@SerializedObject
public class User {
    public int id;
    public String name;
    public String email;
}

JsonAdapter<User> adapter = new JsonAdapter<>(User.class, JsonConfig.defaults());

User user = adapter.readValue("{\"id\":1,\"name\":\"Jane\",\"email\":\"jane@example.com\"}");
String json = adapter.writeValue(user); // {"id":1,"name":"Jane","email":"jane@example.com"}
```

---

## Benchmarks

> Full results and methodology: [benchmarks/Benchmarks.md](benchmarks/Benchmarks.md)

All benchmarks run with JMH in throughput mode (`ops/ms`, higher is better) on Java 21 with SIMD enabled.

![Speedup vs Jackson](benchmarks/bench_speedup.png)

| Category | Uniform | Jackson | simdjson-java¹ | Gson |
|---|---|---|---|---|
| Read — Simple | **5,984** | 3,475 | 3,506 | 2,236 |
| Read — Complex | **2,918** | 1,538 | 1,661 | 1,191 |
| Write — Simple | **19,268** | 5,390 | — | 3,410 |
| Write — Complex | **5,111** | 2,971 | — | 1,286 |
| Pretty-print | **1,027** | 338 | — | 227 |

¹ [simdjson-java](https://github.com/simdjson/simdjson-java) is the official Java port of simdjson — it is a pure parser with no write path.

---

## Features

- **Compile-time code generation** — annotation processor generates a dedicated reader and writer per class; zero reflection at runtime
- **SIMD-accelerated scanning** — structural character classification and string boundary detection via `jdk.incubator.vector`
- **Collection support** — `List`, `Set`, `Queue`, `Map<String, V>`, and primitive/object arrays
- **Nested and recursive types** — generated readers wire directly to each other, no registry lookup in the hot path
- **Interface / abstract fields** — resolved via `@Resolves` or `@ContextDynamicSupplier`
- **Configurable read features** — single quotes, unquoted field names, trailing commas, comments (Java + YAML), leading zeros, NaN/Infinity, and more
- **Configurable write features** — null value handling, empty collection suppression, Unicode escaping, numbers-as-strings
- **Pretty printing** — `JsonFormatter` with configurable indent, ~3× faster than Jackson
- **Records** — full support alongside regular classes

---

## Installation

> Requires Java 21+. SIMD acceleration requires `--add-modules jdk.incubator.vector`.

### Gradle (Kotlin DSL)

```kotlin
dependencies {
    implementation("io.github.flameyossnowy.uniform:json:1.5.5")
    annotationProcessor("io.github.flameyossnowy.uniform:annotation-processor:1.5.5")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--add-modules")
    options.compilerArgs.add("jdk.incubator.vector")
}

tasks.withType<Test> {
    jvmArgs("--add-modules", "jdk.incubator.vector")
}

application {
    applicationDefaultJvmArgs = listOf("--add-modules", "jdk.incubator.vector")
}
```

### Maven

```xml
<dependency>
    <groupId>io.github.flameyossnowy.uniform</groupId>
    <artifactId>json</artifactId>
    <version>1.5.5</version>
</dependency>

<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>io.github.flameyossnowy.uniform</groupId>
                <artifactId>annotation-processor</artifactId>
                <version>1.5.5</version>
            </path>
        </annotationProcessorPaths>
        <compilerArgs>
            <arg>--add-modules</arg>
            <arg>jdk.incubator.vector</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

---

## Usage

### 1. Annotate your class

```java
import annotations.io.github.flameyossnowy.uniform.core.SerializedObject;

@SerializedObject
public class Product {
    public int id;
    public String name;
    public double price;
    public boolean available;

    public Product() {}
    public Product(int id, String name, double price, boolean available) {
        this.id = id; this.name = name;
        this.price = price; this.available = available;
    }
}
```

Records work identically:

```java
@SerializedObject
public record Product(int id, String name, double price, boolean available) {}
```

### 2. Create an adapter

```java
// Default config - strict JSON, no extra features
JsonAdapter<Product> adapter = new JsonAdapter<>(JsonConfig.defaults());

// Or build a custom config
JsonConfig config = JsonAdapter.builder()
    .addReadFeature(JsonReadFeature.ALLOW_JAVA_COMMENTS)
    .build();

JsonAdapter<Product> adapter = new JsonAdapter<>(config);
```

### 3. Read and write

```java
// Read from String
Product p = adapter.readValue("{\"id\":1,\"name\":\"Widget\",\"price\":9.99,\"available\":true}");

// Read from byte[]
byte[] bytes = Files.readAllBytes(Path.of("product.json"));
Product p = adapter.readValue(bytes, Product.class);

// Write to String
String json = adapter.writeValue(p);
// -> {"id":1,"name":"Widget","price":9.99,"available":true}
```

### 4. Collections

All standard collection fields are supported with no extra configuration:

```java
@SerializedObject
public class Catalog {
    public List<Product>        items;
    public Set<String>          tags;
    public Queue<String>        queue;
    public Map<String, Product> index;
    public int[]                counts;
    public String[]             labels;
}
```

### 5. Nested objects

Nested `@SerializedObject` types are wired together automatically at compile time:

```java
@SerializedObject
public class Order {
    public int id;
    public Product product;
    public int quantity;
}
```

### 6. Custom field names

```java
@SerializedObject
public class User {
    @SerializedName("user_id")
    public int id;

    @SerializedName("full_name")
    public String name;
}
```

### 7. Ignoring fields

```java
@SerializedObject
public class Session {
    public String token;

    @IgnoreSerializedField
    public transient long cachedHash; // never serialized
}
```

### 8. Interface / abstract fields

```java
@ContextDynamicSupplier(Shape.class)
public class ShapeSupplier implements ContextDynamicTypeSupplier<Shape> {
    @Override
    public Class<? extends Shape> supply(ResolutionContext ctx) {
        // inspect ctx.rawJson() or ctx.fieldName() to decide concrete type
        return Circle.class;
    }
}
```

### 9. Pretty printing

```java
JsonWriter writer = adapter.createWriter(JsonWriterOptions.PRETTY);
String pretty = writer.write(product);
```

Or format an existing JSON string:

```java
JsonFormatter formatter = new JsonFormatter(Path.of("unused"), 4); // 4-space indent
String formatted = formatter.formatToString(rawJson);
```

### 10. Read/write feature flags

```java
JsonConfig config = new JsonConfig(
    false,  // pretty print
    2,      // indent spaces
    EnumSet.of(
        JsonReadFeature.ALLOW_JAVA_COMMENTS,
        JsonReadFeature.ALLOW_TRAILING_COMMA,
        JsonReadFeature.ALLOW_SINGLE_QUOTES
    ),
    EnumSet.of(
        JsonWriteFeature.WRITE_NULL_MAP_VALUES
    )
);
```

---

## How it works

```
Your POJO  annotationprocessor ──▶   MyPojo_JsonReader.java
                                      MyPojo_JsonWriter.java
                                      MyPojo_JsonModule.java
                                           │
                        ┌──────────────────▼──────────────────┐
                        │         JsonMapperRegistry          │
                        │   (ServiceLoader + static maps)     │
                        └──────────────────┬──────────────────┘
                                           │
                        ┌──────────────────▼───────────────────┐
                        │           JsonAdapter<T>             │
                        │  readValue()  ──▶  JsonCursor       │
                        │                    (SIMD scan)       │
                        │  writeValue() ──▶  JsonStringWriter │
                        │                    (Create buffer)   │
                        └──────────────────────────────────────┘
```

**Read path:** `VectorByteScanner` makes one SIMD pass over the input to build structural and quote bitmasks. `JsonCursor` walks those bitmasks with `Long.numberOfTrailingZeros` dispatch — no byte-by-byte scanning in the hot path. The generated reader dispatches on FNV-1a field name hashes via a `switch` statement.

**Write path:** `JsonStringWriter` creates a new `StringBuilder`. String escaping uses a 256-entry lookup table for the fast path. The generated writer calls `out.nameAscii()` / `out.value()` directly with no intermediate representation.

---

## Contributing

Contributions are welcome. Please open an issue before starting work on a large change so we can discuss direction first.

**Setup:**

```bash
git clone https://github.com/FlameyosSnowy/Uniform.git
cd Uniform
./gradlew build
```

**Running tests:**

```bash
./gradlew test
```

**Running benchmarks:**

```bash
./gradlew :json:jmh
# Results -> json/build/results/jmh/results.json

# Pretty-print only
./gradlew :json:jmhPrettyPrint
```

**Guidelines:**
- Keep the hot path allocation-free — verify with `-XX:+PrintCompilation` or async-profiler before submitting
- New collection types or read/write features must include both a smoke test and a round-trip test
- Processor changes must regenerate cleanly under incremental compilation (`./gradlew clean build`)
- Match the existing code style — no Lombok, no extra dependencies in `core` or `json`

---

## License

```
MIT License

Copyright (c) 2026 FlameyosFlow

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
