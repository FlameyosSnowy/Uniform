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

    private JsonAdapter<SimpleBenchPojo>      uniformSimple;
    private JsonAdapter<ComplexBenchPojo>     uniformComplex;
    private JsonAdapter<SuperComplexBenchPojo> uniformSuperComplex;

    private String simpleJson;
    private String complexJson;
    private String superComplexJson;

    private byte[] simpleJsonBytes;
    private byte[] complexJsonBytes;
    private byte[] superComplexJsonBytes;

    private SimpleBenchPojo      simpleObj;
    private ComplexBenchPojo     complexObj;
    private SuperComplexBenchPojo superComplexObj;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        JsonFactory factory = JsonFactory.builder()
            .recyclerPool(JsonRecyclerPools.threadLocalPool())
            .build();

        jackson = JsonMapper.builder(factory)
            .disable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();
        gson     = new Gson();
        simdJson = new SimdJsonParser();

        JsonConfig cfg = new JsonConfig(false, 2,
            EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));

        uniformSimple       = new JsonAdapter<>(SimpleBenchPojo.class, cfg);
        uniformComplex      = new JsonAdapter<>(ComplexBenchPojo.class, cfg);
        uniformSuperComplex = new JsonAdapter<>(SuperComplexBenchPojo.class, cfg);

        simpleJson  = "{\"id\":1,\"name\":\"a\"}";
        complexJson = "{\"id\":1,\"name\":\"a\",\"child\":{\"id\":2,\"name\":\"b\"},\"count\":42}";

        superComplexObj = new SuperComplexBenchPojo(
            1001,
            "Jane Doe",
            "jane.doe@example.com",
            "2024-03-15T10:30:00Z",
            true,
            98.76,
            34,
            "admin",
            "Loves distributed systems and coffee. Open source contributor. Avid hiker.",
            "https://cdn.example.com/avatars/jane_doe.png",
            new AddressPojo("742 Evergreen Terrace", "Springfield", "IL", "62704", "US", 39.7817, -89.6501),
            List.of(
                new TagPojo(1, "backend",    "#3498db"),
                new TagPojo(2, "java",       "#e74c3c"),
                new TagPojo(3, "open-source","#2ecc71"),
                new TagPojo(4, "devops",     "#9b59b6")
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
                    new OrderItemPojo(1, "SKU-AAA", "Wireless Keyboard",  1, 79.99,  79.99),
                    new OrderItemPojo(2, "SKU-BBB", "USB-C Hub",          1, 39.99,  39.99),
                    new OrderItemPojo(3, "SKU-CCC", "Screen Cleaning Kit", 2,  15.00, 30.00)
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

        superComplexJson      = jackson.writeValueAsString(superComplexObj);
        superComplexJsonBytes = superComplexJson.getBytes(StandardCharsets.UTF_8);

        simpleJsonBytes  = simpleJson.getBytes(StandardCharsets.UTF_8);
        complexJsonBytes = complexJson.getBytes(StandardCharsets.UTF_8);

        simpleObj  = new SimpleBenchPojo(1, "a");
        complexObj = new ComplexBenchPojo(1, "a", new SimpleBenchPojo(2, "b"), 42);
    }

    // ── Gson ────────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo      gson_read_simple()        { return gson.fromJson(simpleJson,       SimpleBenchPojo.class); }
    @Benchmark public ComplexBenchPojo     gson_read_complex()       { return gson.fromJson(complexJson,      ComplexBenchPojo.class); }
    @Benchmark public SuperComplexBenchPojo gson_read_super_complex() { return gson.fromJson(superComplexJson, SuperComplexBenchPojo.class); }

    @Benchmark public String gson_write_simple()        { return gson.toJson(simpleObj); }
    @Benchmark public String gson_write_complex()       { return gson.toJson(complexObj); }
    @Benchmark public String gson_write_super_complex() { return gson.toJson(superComplexObj); }

    // ── Uniform ─────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo       uniform_read_simple()        { return uniformSimple.readValue(simpleJsonBytes); }
    @Benchmark public ComplexBenchPojo      uniform_read_complex()       { return uniformComplex.readValue(complexJsonBytes); }
    @Benchmark public SuperComplexBenchPojo uniform_read_super_complex() { return uniformSuperComplex.readValue(superComplexJsonBytes); }

    @Benchmark public String uniform_write_simple()        { return uniformSimple.writeValue(simpleObj); }
    @Benchmark public String uniform_write_complex()       { return uniformComplex.writeValue(complexObj); }
    @Benchmark public String uniform_write_super_complex() { return uniformSuperComplex.writeValue(superComplexObj); }

    // ── Jackson ─────────────────────────────────────────────────────────────

    @Benchmark public SimpleBenchPojo       jackson_read_simple()        throws Exception { return jackson.readValue(simpleJson,       SimpleBenchPojo.class); }
    @Benchmark public ComplexBenchPojo      jackson_read_complex()       throws Exception { return jackson.readValue(complexJson,      ComplexBenchPojo.class); }
    @Benchmark public SuperComplexBenchPojo jackson_read_super_complex() throws Exception { return jackson.readValue(superComplexJson,  SuperComplexBenchPojo.class); }

    @Benchmark public String jackson_write_simple()        throws Exception { return jackson.writeValueAsString(simpleObj); }
    @Benchmark public String jackson_write_complex()       throws Exception { return jackson.writeValueAsString(complexObj); }
    @Benchmark public String jackson_write_super_complex() throws Exception { return jackson.writeValueAsString(superComplexObj); }

    // ── simdjson-java (read-only) ────────────────────────────────────────────

    @Benchmark
    public SimpleBenchPojo simdjson_read_simple() {
        JsonValue root = simdJson.parse(simpleJsonBytes, simpleJsonBytes.length);
        return new SimpleBenchPojo(
            (int) root.get("id").asLong(),
            root.get("name").asString()
        );
    }

    @Benchmark
    public ComplexBenchPojo simdjson_read_complex() {
        JsonValue root  = simdJson.parse(complexJsonBytes, complexJsonBytes.length);
        JsonValue child = root.get("child");
        return new ComplexBenchPojo(
            (int) root.get("id").asLong(),
            root.get("name").asString(),
            new SimpleBenchPojo((int) child.get("id").asLong(), child.get("name").asString()),
            (int) root.get("count").asLong()
        );
    }

    @Benchmark
    public SuperComplexBenchPojo simdjson_read_super_complex() {
        JsonValue r = simdJson.parse(superComplexJsonBytes, superComplexJsonBytes.length);

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
            meta.get("source").asString(),   meta.get("version").asString(),
            (int) meta.get("revision").asLong(), meta.get("verified").asBoolean(),
            meta.get("locale").asString(),   meta.get("timezone").asString(),
            meta.get("lastLogin").asLong(),  (int) meta.get("loginCount").asLong()
        );

        JsonValue ordersArr = r.get("orders");
        List<OrderPojo> orders = new java.util.ArrayList<>((int) ordersArr.getSize());
        for (Iterator<JsonValue> iter = ordersArr.arrayIterator(); iter.hasNext(); ) {
            JsonValue o = iter.next();
            JsonValue itemsArr = o.get("items");
            List<OrderItemPojo> items = new java.util.ArrayList<>((int) itemsArr.getSize());
            for (Iterator<JsonValue> iterator = itemsArr.arrayIterator(); iterator.hasNext(); ) {
                JsonValue it = iterator.next();
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
}