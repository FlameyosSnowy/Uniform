package me.flame.uniform.json.codegen;

import me.flame.uniform.json.JsonAdapter;
import me.flame.uniform.json.JsonConfig;
import me.flame.uniform.json.codegen.fixtures.*;
import me.flame.uniform.json.writers.prettifiers.JsonFormatter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCollectionSmokeTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2,
            EnumSet.noneOf(me.flame.uniform.json.features.JsonReadFeature.class),
            EnumSet.noneOf(me.flame.uniform.json.features.JsonWriteFeature.class));
    }

    // ── List ─────────────────────────────────────────────────────────────────

    @Test
    void list_round_trip() {
        JsonAdapter<PojoWithList> adapter = new JsonAdapter<>(PojoWithList.class, config());

        PojoWithList original = new PojoWithList(
            "test",
            List.of("alpha", "beta", "gamma"),
            List.of(10, 20, 30),
            List.of(new SimplePojo(1, "a"), new SimplePojo(2, "b"))
        );

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"label\":\"test\""));
        assertTrue(json.contains("\"tags\":[\"alpha\",\"beta\",\"gamma\"]"));
        assertTrue(json.contains("\"scores\":[10,20,30]"));

        String formattedJson = new JsonFormatter(Path.of("nothing"), 8).formatToString(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        System.out.println(formattedJson);

        PojoWithList read = adapter.readValue(json);
        assertNotNull(read);
        assertEquals("test", read.label);
        assertEquals(List.of("alpha", "beta", "gamma"), read.tags);
        assertEquals(List.of(10, 20, 30), read.scores);
        assertEquals(2, read.children.size());
        assertEquals(1, read.children.get(0).id);
        assertEquals("b", read.children.get(1).name);
    }

    @Test
    void list_null_field() {
        JsonAdapter<PojoWithList> adapter = new JsonAdapter<>(PojoWithList.class, config());
        PojoWithList read = adapter.readValue("{\"label\":\"x\",\"tags\":null,\"scores\":null,\"children\":null}");
        assertNotNull(read);
        assertEquals("x", read.label);
        assertNull(read.tags);
        assertNull(read.scores);
    }

    @Test
    void list_empty() {
        JsonAdapter<PojoWithList> adapter = new JsonAdapter<>(PojoWithList.class, config());
        PojoWithList read = adapter.readValue("{\"label\":\"x\",\"tags\":[],\"scores\":[],\"children\":[]}");
        assertNotNull(read.tags);
        assertTrue(read.tags.isEmpty());
        assertTrue(read.scores.isEmpty());
        assertTrue(read.children.isEmpty());
    }

    // ── Set ──────────────────────────────────────────────────────────────────

    @Test
    void set_round_trip() {
        JsonAdapter<PojoWithSet> adapter = new JsonAdapter<>(PojoWithSet.class, config());

        PojoWithSet original = new PojoWithSet(
            "perms",
            new LinkedHashSet<>(List.of("read", "write", "exec")),
            new LinkedHashSet<>(List.of(1, 2, 3))
        );

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"label\":\"perms\""));

        PojoWithSet read = adapter.readValue(json);
        assertNotNull(read);
        assertEquals("perms", read.label);
        assertEquals(Set.of("read", "write", "exec"), read.permissions);
        assertEquals(Set.of(1, 2, 3), read.ids);
    }

    @Test
    void set_deduplicates_on_read() {
        JsonAdapter<PojoWithSet> adapter = new JsonAdapter<>(PojoWithSet.class, config());
        // Duplicate "read" — Set must collapse it
        PojoWithSet read = adapter.readValue(
            "{\"label\":\"x\",\"permissions\":[\"read\",\"write\",\"read\"],\"ids\":[1,2,1]}");
        assertEquals(2, read.permissions.size());
        assertEquals(2, read.ids.size());
    }

    // ── Array ────────────────────────────────────────────────────────────────

    @Test
    void array_round_trip() {
        JsonAdapter<PojoWithArray> adapter = new JsonAdapter<>(PojoWithArray.class, config());

        PojoWithArray original = new PojoWithArray(
            "arr",
            new int[]{1, 2, 3},
            new double[]{1.1, 2.2, 3.3},
            new String[]{"x", "y", "z"}
        );

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"counts\":[1,2,3]"));
        assertTrue(json.contains("\"names\":[\"x\",\"y\",\"z\"]"));

        PojoWithArray read = adapter.readValue(json);
        assertNotNull(read);
        assertArrayEquals(new int[]{1, 2, 3}, read.counts);
        assertArrayEquals(new double[]{1.1, 2.2, 3.3}, read.ratios, 1e-9);
        assertArrayEquals(new String[]{"x", "y", "z"}, read.names);
    }

    @Test
    void array_empty() {
        JsonAdapter<PojoWithArray> adapter = new JsonAdapter<>(PojoWithArray.class, config());
        PojoWithArray read = adapter.readValue(
            "{\"label\":\"e\",\"counts\":[],\"ratios\":[],\"names\":[]}");
        assertArrayEquals(new int[0], read.counts);
        assertArrayEquals(new double[0], read.ratios);
        assertArrayEquals(new String[0], read.names);
    }

    // ── Map ──────────────────────────────────────────────────────────────────

    @Test
    void map_round_trip() {
        JsonAdapter<PojoWithMap> adapter = new JsonAdapter<>(PojoWithMap.class, config());

        PojoWithMap original = new PojoWithMap(
            "maps",
            new LinkedHashMap<>(Map.of("hits", 10, "misses", 3)),
            new LinkedHashMap<>(Map.of("env", "prod", "region", "eu-west")),
            new LinkedHashMap<>(Map.of("alice", new SimplePojo(1, "alice"), "bob", new SimplePojo(2, "bob")))
        );

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"label\":\"maps\""));

        String formattedJson = new JsonFormatter(Path.of("nothing"), 2).formatToString(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        System.out.println(formattedJson);

        PojoWithMap read = adapter.readValue(json);

        assertNotNull(read);
        assertEquals("maps", read.label);
        assertEquals(10, read.counters.get("hits"));
        assertEquals(3,  read.counters.get("misses"));
        assertEquals("prod",    read.metadata.get("env"));
        assertEquals("eu-west", read.metadata.get("region"));
        assertEquals(1, read.index.get("alice").id);
        assertEquals("bob", read.index.get("bob").name);
    }

    @Test
    void map_empty() {
        JsonAdapter<PojoWithMap> adapter = new JsonAdapter<>(PojoWithMap.class, config());
        PojoWithMap read = adapter.readValue(
            "{\"label\":\"e\",\"counters\":{},\"metadata\":{},\"index\":{}}");
        assertNotNull(read.counters);
        assertTrue(read.counters.isEmpty());
        assertTrue(read.metadata.isEmpty());
        assertTrue(read.index.isEmpty());
    }

    // ── Queue ────────────────────────────────────────────────────────────────

    @Test
    void queue_round_trip() {
        JsonAdapter<PojoWithQueue> adapter = new JsonAdapter<>(PojoWithQueue.class, config());

        Queue<String>  tasks      = new ArrayDeque<>(List.of("build", "test", "deploy"));
        Queue<Integer> priorities = new ArrayDeque<>(List.of(1, 2, 3));
        PojoWithQueue original = new PojoWithQueue("q", tasks, priorities);

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"tasks\":[\"build\",\"test\",\"deploy\"]"));

        PojoWithQueue read = adapter.readValue(json);
        assertNotNull(read);
        assertEquals("q", read.label);
        // Queue ordering must be preserved (ArrayDeque is FIFO)
        assertEquals(List.of("build", "test", "deploy"), new ArrayList<>(read.tasks));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(read.priorities));
    }

    @Test
    void queue_empty() {
        JsonAdapter<PojoWithQueue> adapter = new JsonAdapter<>(PojoWithQueue.class, config());
        PojoWithQueue read = adapter.readValue(
            "{\"label\":\"e\",\"tasks\":[],\"priorities\":[]}");
        assertNotNull(read.tasks);
        assertTrue(read.tasks.isEmpty());
        assertTrue(read.priorities.isEmpty());
    }
}