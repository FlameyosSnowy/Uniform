package me.flame.uniform.json.bench;

import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.util.JsonRecyclerPools;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.bench.fixtures.*;
import org.openjdk.jmh.annotations.*;
import org.simdjson.JsonValue;
import org.simdjson.SimdJsonParser;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.nio.charset.StandardCharsets;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 1)
@State(Scope.Benchmark)
public class JacksonVsUniformReadWriteBenchmark {

    private ObjectMapper jackson;
    private Gson gson;
    private SimdJsonParser simdJson;
    private JsonAdapter uniform;

    private String simpleJson;
    private String complexJson;
    String superComplexJson;   // package-private for DumpFixtures
    String mediumJson;
    String largeJson;

    private byte[] simpleJsonBytes;
    private byte[] complexJsonBytes;
    byte[] superComplexJsonBytes;
    byte[] mediumJsonBytes;
    byte[] largeJsonBytes;

    private SimpleBenchPojo       simpleObj;
    private ComplexBenchPojo      complexObj;
    private SuperComplexBenchPojo superComplexObj;
    private MediumBenchPojo       mediumObj;

    // ── Fixture construction helpers ─────────────────────────────────────────

    static SuperComplexBenchPojo buildSuperComplex() {
        return new SuperComplexBenchPojo(
            1001, "Jane Doe", "jane.doe@example.com", "2024-03-15T10:30:00Z",
            true, 98.76, 34, "admin",
            "Loves distributed systems and coffee. Open source contributor. Avid hiker.",
            "https://cdn.example.com/avatars/jane_doe.png",
            new AddressPojo("742 Evergreen Terrace", "Springfield", "IL", "62704", "US", 39.7817, -89.6501),
            List.of(
                new TagPojo(1, "backend",     "#3498db"),
                new TagPojo(2, "java",        "#e74c3c"),
                new TagPojo(3, "open-source", "#2ecc71"),
                new TagPojo(4, "devops",      "#9b59b6")
            ),
            List.of(
                new SimpleBenchPojo(10, "Alice"),
                new SimpleBenchPojo(11, "Bob"),
                new SimpleBenchPojo(12, "Charlie"),
                new SimpleBenchPojo(13, "Diana")
            ),
            new MetadataPojo("web", "3.1.4", 17, true, "en-US", "America/Chicago", 1710498600000L, 142),
            List.of(
                new OrderPojo(5001, "delivered", 149.99, "USD", "2024-01-10T08:00:00Z", List.of(
                    new OrderItemPojo(1, "SKU-AAA", "Wireless Keyboard",   1, 79.99,  79.99),
                    new OrderItemPojo(2, "SKU-BBB", "USB-C Hub",           1, 39.99,  39.99),
                    new OrderItemPojo(3, "SKU-CCC", "Screen Cleaning Kit", 2, 15.00,  30.00)
                )),
                new OrderPojo(5002, "shipped", 249.50, "USD", "2024-02-20T14:30:00Z", List.of(
                    new OrderItemPojo(4, "SKU-DDD", "Mechanical Keyboard", 1, 149.50, 149.50),
                    new OrderItemPojo(5, "SKU-EEE", "Desk Mat XL",         1, 100.00, 100.00)
                )),
                new OrderPojo(5003, "pending", 59.99, "USD", "2024-03-14T22:15:00Z", List.of(
                    new OrderItemPojo(6, "SKU-FFF", "Cable Management Kit", 3, 19.99, 59.97)
                ))
            )
        );
    }

    static MediumBenchPojo buildMedium() {
        return new MediumBenchPojo(
            42, "Alice Smith", "alice@example.com",
            new AddressPojo("123 Main St", "Springfield", "IL", "62701", "US", 0.0, 0.0),
            List.of(
                new OrderItemPojo(1, "WIDGET-A",  "Wireless Keyboard",   2, 9.99,  19.98),
                new OrderItemPojo(2, "WIDGET-B",  "USB-C Hub",           1, 24.99, 24.99),
                new OrderItemPojo(3, "GADGET-X",  "Screen Cleaning Kit", 5, 4.49,  22.45),
                new OrderItemPojo(4, "GADGET-Y",  "Desk Mat XL",         3, 14.99, 44.97),
                new OrderItemPojo(5, "DOOHICKEY", "Cable Management",   10, 1.99,  19.90)
            ),
            "CONFIRMED", "2025-02-26T10:00:00Z", "Please leave at door"
        );
    }

    static String buildLargeJson(ObjectMapper jackson, int count) throws Exception {
        java.util.Random rng = new java.util.Random(42);
        String alpha = "abcdefghijklmnopqrstuvwxyz";
        java.util.function.BiFunction<java.util.Random, Integer, String> rs = (r, n) -> {
            StringBuilder sb = new StringBuilder(n);
            for (int i = 0; i < n; i++) sb.append(alpha.charAt(r.nextInt(26)));
            return sb.toString();
        };
        String[] countries = {"US", "UK", "DE", "FR", "JP"};
        java.util.List<java.util.Map<String, Object>> users = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            java.util.Map<String, Object> u = new java.util.LinkedHashMap<>();
            u.put("id",       i);
            u.put("username", rs.apply(rng, 10));
            u.put("email",    rs.apply(rng, 8) + "@" + rs.apply(rng, 6) + ".com");
            u.put("age",      18 + rng.nextInt(63));
            u.put("active",   rng.nextBoolean());
            u.put("score",    Math.round(rng.nextDouble() * 100 * 10000.0) / 10000.0);
            int tagCount = 1 + rng.nextInt(5);
            java.util.List<String> tags = new java.util.ArrayList<>(tagCount);
            for (int t = 0; t < tagCount; t++) tags.add(rs.apply(rng, 5));
            u.put("tags", tags);
            java.util.Map<String, Object> addr = new java.util.LinkedHashMap<>();
            addr.put("street",  (1 + rng.nextInt(999)) + " " + rs.apply(rng, 8) + " St");
            addr.put("city",    rs.apply(rng, 8));
            addr.put("country", countries[rng.nextInt(countries.length)]);
            addr.put("zip",     String.valueOf(10000 + rng.nextInt(90000)));
            u.put("address", addr);
            java.util.Map<String, String> meta = new java.util.LinkedHashMap<>();
            for (int m = 0; m < 3; m++) meta.put(rs.apply(rng, 4), rs.apply(rng, 6));
            u.put("metadata", meta);
            users.add(u);
        }
        return jackson.writeValueAsString(users);
    }

    // ── Setup ────────────────────────────────────────────────────────────────

    @Setup(Level.Trial)
    public void setup() throws Exception {
        JsonFactory factory = JsonFactory.builder()
            .recyclerPool(JsonRecyclerPools.threadLocalPool())
            .build();
        jackson  = JsonMapper.builder(factory)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        gson     = new Gson();
        simdJson = new SimdJsonParser();

        JsonConfig cfg = new JsonConfig(false, 2,
            EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));
        uniform = new JsonAdapter(cfg);

        simpleJson  = "{\"id\":1,\"name\":\"a\"}";
        complexJson = "{\"id\":1,\"name\":\"a\",\"child\":{\"id\":2,\"name\":\"b\"},\"count\":42}";

        superComplexObj      = buildSuperComplex();
        superComplexJson     = jackson.writeValueAsString(superComplexObj);
        superComplexJsonBytes = superComplexJson.getBytes(StandardCharsets.UTF_8);

        mediumObj   = buildMedium();
        mediumJson  = jackson.writeValueAsString(mediumObj);
        mediumJsonBytes = mediumJson.getBytes(StandardCharsets.UTF_8);

        largeJson       = buildLargeJson(jackson, 1000);
        largeJsonBytes  = largeJson.getBytes(StandardCharsets.UTF_8);

        simpleJsonBytes  = simpleJson.getBytes(StandardCharsets.UTF_8);
        complexJsonBytes = complexJson.getBytes(StandardCharsets.UTF_8);

        simpleObj  = new SimpleBenchPojo(1, "a");
        complexObj = new ComplexBenchPojo(1, "a", new SimpleBenchPojo(2, "b"), 42);

        // Write shared fixtures so C++ bench uses identical bytes
        Path dir = Path.of(System.getProperty("user.home"), "Uniform", "data");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("super_complex.json"), superComplexJson);
        Files.writeString(dir.resolve("medium.json"),        mediumJson);
        Files.writeString(dir.resolve("large.json"),         largeJson);
    }

    // ── Gson ─────────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo       gson_read_simple()        { return gson.fromJson(simpleJson,       SimpleBenchPojo.class); }
    @Benchmark public ComplexBenchPojo      gson_read_complex()       { return gson.fromJson(complexJson,      ComplexBenchPojo.class); }
    @Benchmark public SuperComplexBenchPojo gson_read_super_complex() { return gson.fromJson(superComplexJson, SuperComplexBenchPojo.class); }
    @Benchmark public MediumBenchPojo       gson_read_medium()        { return gson.fromJson(mediumJson,       MediumBenchPojo.class); }

    @Benchmark public String gson_write_simple()        { return gson.toJson(simpleObj); }
    @Benchmark public String gson_write_complex()       { return gson.toJson(complexObj); }
    @Benchmark public String gson_write_super_complex() { return gson.toJson(superComplexObj); }
    @Benchmark public String gson_write_medium()        { return gson.toJson(mediumObj); }

    // ── Uniform ──────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo       uniform_read_simple()        { return uniform.readValue(simpleJsonBytes,       SimpleBenchPojo.class); }
    @Benchmark public ComplexBenchPojo      uniform_read_complex()       { return uniform.readValue(complexJsonBytes,      ComplexBenchPojo.class); }
    @Benchmark public SuperComplexBenchPojo uniform_read_super_complex() { return uniform.readValue(superComplexJsonBytes, SuperComplexBenchPojo.class); }
    @Benchmark public MediumBenchPojo       uniform_read_medium()        { return uniform.readValue(mediumJsonBytes,       MediumBenchPojo.class); }

    @Benchmark public String uniform_write_simple()        { return uniform.writeValue(simpleObj); }
    @Benchmark public String uniform_write_complex()       { return uniform.writeValue(complexObj); }
    @Benchmark public String uniform_write_super_complex() { return uniform.writeValue(superComplexObj); }
    @Benchmark public String uniform_write_medium()        { return uniform.writeValue(mediumObj); }

    // ── Jackson ──────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo       jackson_read_simple()        throws Exception { return jackson.readValue(simpleJson,       SimpleBenchPojo.class); }
    @Benchmark public ComplexBenchPojo      jackson_read_complex()       throws Exception { return jackson.readValue(complexJson,      ComplexBenchPojo.class); }
    @Benchmark public SuperComplexBenchPojo jackson_read_super_complex() throws Exception { return jackson.readValue(superComplexJson,  SuperComplexBenchPojo.class); }
    @Benchmark public MediumBenchPojo       jackson_read_medium()        throws Exception { return jackson.readValue(mediumJson,        MediumBenchPojo.class); }

    @Benchmark public String jackson_write_simple()        throws Exception { return jackson.writeValueAsString(simpleObj); }
    @Benchmark public String jackson_write_complex()       throws Exception { return jackson.writeValueAsString(complexObj); }
    @Benchmark public String jackson_write_super_complex() throws Exception { return jackson.writeValueAsString(superComplexObj); }
    @Benchmark public String jackson_write_medium()        throws Exception { return jackson.writeValueAsString(mediumObj); }

    // ── simdjson-java (read-only) ────────────────────────────────────────────

    @Benchmark
    public SimpleBenchPojo simdjson_read_simple() {
        JsonValue root = simdJson.parse(simpleJsonBytes, simpleJsonBytes.length);
        return new SimpleBenchPojo((int) root.get("id").asLong(), root.get("name").asString());
    }

    @Benchmark
    public ComplexBenchPojo simdjson_read_complex() {
        JsonValue root  = simdJson.parse(complexJsonBytes, complexJsonBytes.length);
        JsonValue child = root.get("child");
        return new ComplexBenchPojo(
            (int) root.get("id").asLong(), root.get("name").asString(),
            new SimpleBenchPojo((int) child.get("id").asLong(), child.get("name").asString()),
            (int) root.get("count").asLong()
        );
    }

    @Benchmark
    public SuperComplexBenchPojo simdjson_read_super_complex() {
        JsonValue r    = simdJson.parse(superComplexJsonBytes, superComplexJsonBytes.length);
        JsonValue addr = r.get("address");
        AddressPojo address = new AddressPojo(
            addr.get("street").asString(), addr.get("city").asString(),
            addr.get("state").asString(),  addr.get("zip").asString(),
            addr.get("country").asString(),
            addr.get("lat").asDouble(),    addr.get("lon").asDouble()
        );
        JsonValue tagsArr = r.get("tags");
        List<TagPojo> tags = new java.util.ArrayList<>((int) tagsArr.getSize());
        for (Iterator<JsonValue> it = tagsArr.arrayIterator(); it.hasNext(); ) {
            JsonValue t = it.next();
            tags.add(new TagPojo((int) t.get("id").asLong(), t.get("label").asString(), t.get("color").asString()));
        }
        JsonValue friendsArr = r.get("friends");
        List<SimpleBenchPojo> friends = new java.util.ArrayList<>((int) friendsArr.getSize());
        for (Iterator<JsonValue> it = friendsArr.arrayIterator(); it.hasNext(); ) {
            JsonValue f = it.next();
            friends.add(new SimpleBenchPojo((int) f.get("id").asLong(), f.get("name").asString()));
        }
        JsonValue meta = r.get("metadata");
        MetadataPojo metadata = new MetadataPojo(
            meta.get("source").asString(),       meta.get("version").asString(),
            (int) meta.get("revision").asLong(), meta.get("verified").asBoolean(),
            meta.get("locale").asString(),       meta.get("timezone").asString(),
            meta.get("lastLogin").asLong(),      (int) meta.get("loginCount").asLong()
        );
        JsonValue ordersArr = r.get("orders");
        List<OrderPojo> orders = new java.util.ArrayList<>((int) ordersArr.getSize());
        for (Iterator<JsonValue> iter = ordersArr.arrayIterator(); iter.hasNext(); ) {
            JsonValue o       = iter.next();
            JsonValue itemsArr = o.get("items");
            List<OrderItemPojo> items = new java.util.ArrayList<>((int) itemsArr.getSize());
            for (Iterator<JsonValue> it2 = itemsArr.arrayIterator(); it2.hasNext(); ) {
                JsonValue it = it2.next();
                items.add(new OrderItemPojo(
                    (int) it.get("itemId").asLong(), it.get("sku").asString(),
                    it.get("description").asString(), (int) it.get("quantity").asLong(),
                    it.get("unitPrice").asDouble(),   it.get("lineTotal").asDouble()
                ));
            }
            orders.add(new OrderPojo(
                (int) o.get("orderId").asLong(), o.get("status").asString(),
                o.get("total").asDouble(),       o.get("currency").asString(),
                o.get("placedAt").asString(),    items
            ));
        }
        return new SuperComplexBenchPojo(
            (int) r.get("id").asLong(),     r.get("name").asString(),
            r.get("email").asString(),       r.get("createdAt").asString(),
            r.get("active").asBoolean(),     r.get("score").asDouble(),
            (int) r.get("age").asLong(),     r.get("role").asString(),
            r.get("bio").asString(),         r.get("avatarUrl").asString(),
            address, tags, friends, metadata, orders
        );
    }

    @Benchmark
    public MediumBenchPojo simdjson_read_medium() {
        JsonValue r = simdJson.parse(mediumJsonBytes, mediumJsonBytes.length);
        JsonValue addr = r.get("address");
        AddressPojo address = new AddressPojo(
            addr.get("street").asString(), addr.get("city").asString(),
            addr.get("state").asString(),  addr.get("zip").asString(),
            addr.get("country").asString(), 0.0, 0.0
        );
        JsonValue itemsArr = r.get("items");
        List<OrderItemPojo> items = new java.util.ArrayList<>((int) itemsArr.getSize());
        for (Iterator<JsonValue> it = itemsArr.arrayIterator(); it.hasNext(); ) {
            JsonValue i = it.next();
            items.add(new OrderItemPojo(
                (int) i.get("itemId").asLong(), i.get("sku").asString(),
                i.get("description").asString(), (int) i.get("quantity").asLong(),
                i.get("unitPrice").asDouble(),   i.get("lineTotal").asDouble()
            ));
        }
        return new MediumBenchPojo(
            (int) r.get("id").asLong(),     r.get("name").asString(),
            r.get("email").asString(),       address, items,
            r.get("status").asString(),      r.get("createdAt").asString(),
            r.get("notes").asString()
        );
    }
}