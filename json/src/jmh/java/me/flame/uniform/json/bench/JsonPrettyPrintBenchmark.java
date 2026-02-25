package me.flame.uniform.json.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.writers.prettifiers.JsonFormatter;
import org.openjdk.jmh.annotations.*;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(value = 1)
@State(Scope.Benchmark)
public class JsonPrettyPrintBenchmark {

    private ObjectMapper jacksonPretty;
    private Gson gsonPretty;
    private JsonFormatter uniformFormatter;

    private String compactJson;
    private String doubleCompactJson;
    private byte[] compactJsonBytes;
    private byte[] doubleCompactJsonBytes;

    @Setup(Level.Trial)
    public void setup() {
        // Setup Jackson with pretty printing
        jacksonPretty = new ObjectMapper();
        jacksonPretty.enable(SerializationFeature.INDENT_OUTPUT);

        // Setup Gson with pretty printing
        gsonPretty = new GsonBuilder().setPrettyPrinting().setStrictness(Strictness.LENIENT).create();

        // Setup Uniform formatter
        uniformFormatter = new JsonFormatter(null, 4);

        // Test data - single object
        compactJson = "{\"id\":1,\"username\":\"flameyos\",\"email\":\"flameyos@example.com\",\"age\":22,\"active\":true,\"score\":9823.5,\"address\":{\"street\":\"123 Main St\",\"city\":\"Amsterdam\",\"zip\":\"1011AB\",\"country\":\"NL\"},\"metadata\":{\"createdAt\":1708123456789,\"lastLogin\":1708987654321,\"loginCount\":42}}";

        // Test data - double object (as requested)
        doubleCompactJson = compactJson + compactJson;

        compactJsonBytes = compactJson.getBytes(StandardCharsets.UTF_8);
        doubleCompactJsonBytes = doubleCompactJson.getBytes(StandardCharsets.UTF_8);
    }

    @Benchmark
    public String jackson_pretty_single() throws Exception {
        return jacksonPretty.writerWithDefaultPrettyPrinter().writeValueAsString(
            jacksonPretty.readValue(compactJson, Object.class)
        );
    }

    @Benchmark
    public String gson_pretty_single() {
        Object obj = gsonPretty.fromJson(compactJson, Object.class);
        return gsonPretty.toJson(obj);
    }

    @Benchmark
    public String uniform_pretty_single() {
        ByteBuffer result = uniformFormatter.format(ByteBuffer.wrap(compactJsonBytes));
        byte[] outputBytes = new byte[result.remaining()];
        result.get(outputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }

    @Benchmark
    public String jackson_pretty_double() throws Exception {
        return jacksonPretty.writerWithDefaultPrettyPrinter().writeValueAsString(
            jacksonPretty.readValue(doubleCompactJson, Object.class)
        );
    }

    @Benchmark
    public String gson_pretty_double() {
        Object obj = gsonPretty.fromJson(doubleCompactJson, Object.class);
        return gsonPretty.toJson(obj);
    }

    @Benchmark
    public String uniform_pretty_double() {
        ByteBuffer result = uniformFormatter.format(ByteBuffer.wrap(doubleCompactJsonBytes));
        byte[] outputBytes = new byte[result.remaining()];
        result.get(outputBytes);
        return new String(outputBytes, StandardCharsets.UTF_8);
    }
}
