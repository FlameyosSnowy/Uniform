package me.flame.uniform.json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.Gson;
import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.bench.fixtures.ComplexBenchPojo;
import me.flame.uniform.json.bench.fixtures.SimpleBenchPojo;
import org.openjdk.jmh.annotations.*;

import java.util.EnumSet;
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

    private JsonAdapter<SimpleBenchPojo> uniformSimple;
    private JsonAdapter<ComplexBenchPojo> uniformComplex;

    private String simpleJson;
    private String complexJson;

    private byte[] simpleJsonBytes;
    private byte[] complexJsonBytes;

    private SimpleBenchPojo simpleObj;
    private ComplexBenchPojo complexObj;

    @Setup(Level.Trial)
    public void setup() {
        jackson = new ObjectMapper();
        gson = new Gson();

        JsonConfig cfg = new JsonConfig(false, 2,
            EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));

        uniformSimple = new JsonAdapter<>(SimpleBenchPojo.class, cfg);
        uniformComplex = new JsonAdapter<>(ComplexBenchPojo.class, cfg);

        simpleJson = "{\"id\":1,\"name\":\"a\"}";
        complexJson = "{\"id\":1,\"name\":\"a\",\"child\":{\"id\":2,\"name\":\"b\"},\"count\":42}";

        simpleJsonBytes = simpleJson.getBytes(StandardCharsets.UTF_8);
        complexJsonBytes = complexJson.getBytes(StandardCharsets.UTF_8);

        simpleObj = new SimpleBenchPojo(1, "a");
        complexObj = new ComplexBenchPojo(1, "a", new SimpleBenchPojo(2, "b"), 42);
    }

    @Benchmark
    public SimpleBenchPojo gson_read_simple() {
        return gson.fromJson(simpleJson, SimpleBenchPojo.class);
    }

    @Benchmark
    public ComplexBenchPojo gson_read_complex() {
        return gson.fromJson(complexJson, ComplexBenchPojo.class);
    }

    @Benchmark
    public String gson_write_simple() {
        return gson.toJson(simpleObj);
    }

    @Benchmark
    public String gson_write_complex() {
        return gson.toJson(complexObj);
    }

    @Benchmark
    public SimpleBenchPojo uniform_read_simple() {
        return uniformSimple.readValue(simpleJsonBytes);
    }

    @Benchmark
    public ComplexBenchPojo uniform_read_complex() {
        return uniformComplex.readValue(complexJsonBytes);
    }

    @Benchmark
    public String uniform_write_simple() {
        return uniformSimple.writeValue(simpleObj);
    }

    @Benchmark
    public String uniform_write_complex() {
        return uniformComplex.writeValue(complexObj);
    }

    @Benchmark
    public SimpleBenchPojo jackson_read_simple() throws Exception {
        return jackson.readValue(simpleJson, SimpleBenchPojo.class);
    }

    @Benchmark
    public ComplexBenchPojo jackson_read_complex() throws Exception {
        return jackson.readValue(complexJson, ComplexBenchPojo.class);
    }

    @Benchmark
    public String jackson_write_simple() throws Exception {
        return jackson.writeValueAsString(simpleObj);
    }

    @Benchmark
    public String jackson_write_complex() throws Exception {
        return jackson.writeValueAsString(complexObj);
    }
}
