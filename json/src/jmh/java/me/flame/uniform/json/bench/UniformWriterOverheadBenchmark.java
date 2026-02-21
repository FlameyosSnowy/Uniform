package me.flame.uniform.json.bench;

import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.bench.fixtures.SimpleBenchPojo;
import me.flame.uniform.json.mappers.JsonMapperRegistry;
import me.flame.uniform.json.mappers.JsonWriterMapper;
import me.flame.uniform.json.writers.JsonStringWriter;
import org.openjdk.jmh.annotations.*;

import java.util.EnumSet;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 8, time = 2)
@Fork(1)
@State(Scope.Benchmark)
public class UniformWriterOverheadBenchmark {

    private JsonWriterMapper<SimpleBenchPojo> writer;
    private SimpleBenchPojo obj;

    private JsonStringWriter reuse;

    @Setup(Level.Trial)
    public void setup() {
        JsonConfig cfg = new JsonConfig(false, 2,
            EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));

        // Ensure generated modules are bootstrapped and the writer is discoverable.
        new JsonAdapter<>(SimpleBenchPojo.class, cfg);

        writer = (JsonWriterMapper<SimpleBenchPojo>) JsonMapperRegistry.getWriter(SimpleBenchPojo.class);
        if (writer == null) {
            throw new IllegalStateException("No writer for " + SimpleBenchPojo.class);
        }

        obj = new SimpleBenchPojo(1, "a");
        reuse = new JsonStringWriter(64);
    }

    @Benchmark
    public String uniform_write_simple_allocateWriter() {
        JsonStringWriter out = new JsonStringWriter(64);
        writer.writeTo(out, obj);
        return out.finish();
    }

    @Benchmark
    public String uniform_write_simple_reuseWriter() {
        JsonStringWriter out = reuse.reset();
        writer.writeTo(out, obj);
        return out.finish();
    }
}
