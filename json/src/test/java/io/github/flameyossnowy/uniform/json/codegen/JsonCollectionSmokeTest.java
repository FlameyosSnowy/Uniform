package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithArray;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithList;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithMap;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithQueue;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithSet;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.SimplePojo;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.JsonFormatter;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCollectionSmokeTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2,
            EnumSet.noneOf(JsonReadFeature.class),
            EnumSet.noneOf(JsonWriteFeature.class), ReflectionConfig.DEFAULT);
    }

    private static final JsonAdapter ADAPTER = new JsonAdapter(config());

    @Test
    void list_round_trip() {
        PojoWithList original = new PojoWithList(
            "test",
            List.of("alpha", "beta", "gamma"),
            List.of(10, 20, 30),
            List.of(new SimplePojo(1, "a"), new SimplePojo(2, "b"))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println(json);
        assertTrue(json.contains("\"label\":\"test\""));
        assertTrue(json.contains("\"tags\":[\"alpha\",\"beta\",\"gamma\"]"));
        assertTrue(json.contains("\"scores\":[10,20,30]"));

        String formattedJson = new JsonFormatter(Path.of("nothing"), 8).formatToString(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        System.out.println(formattedJson);

        PojoWithList read = ADAPTER.readValue(json, PojoWithList.class);
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
        PojoWithList read = ADAPTER.readValue(
            "{\"label\":\"x\",\"tags\":null,\"scores\":null,\"children\":null}",
            PojoWithList.class);
        assertNotNull(read);
        assertEquals("x", read.label);
        assertNull(read.tags);
        assertNull(read.scores);
    }

    @Test
    void list_empty() {
        PojoWithList read = ADAPTER.readValue(
            "{\"label\":\"x\",\"tags\":[],\"scores\":[],\"children\":[]}",
            PojoWithList.class);
        assertNotNull(read.tags);
        assertTrue(read.tags.isEmpty());
        assertTrue(read.scores.isEmpty());
        assertTrue(read.children.isEmpty());
    }

    @Test
    void set_round_trip() {
        PojoWithSet original = new PojoWithSet(
            "perms",
            new LinkedHashSet<>(List.of("read", "write", "exec")),
            new LinkedHashSet<>(List.of(1, 2, 3))
        );

        String json = ADAPTER.writeValue(original);
        assertTrue(json.contains("\"label\":\"perms\""));

        PojoWithSet read = ADAPTER.readValue(json, PojoWithSet.class);
        assertNotNull(read);
        assertEquals("perms", read.label);
        assertEquals(Set.of("read", "write", "exec"), read.permissions);
        assertEquals(Set.of(1, 2, 3), read.ids);
    }

    @Test
    void set_deduplicates_on_read() {
        PojoWithSet read = ADAPTER.readValue(
            "{\"label\":\"x\",\"permissions\":[\"read\",\"write\",\"read\"],\"ids\":[1,2,1]}",
            PojoWithSet.class);
        assertEquals(2, read.permissions.size());
        assertEquals(2, read.ids.size());
    }

    @Test
    void array_round_trip() {
        PojoWithArray original = new PojoWithArray(
            "arr",
            new int[]{1, 2, 3},
            new double[]{1.1, 2.2, 3.3},
            new String[]{"x", "y", "z"}
        );

        String json = ADAPTER.writeValue(original);
        System.out.println(json);
        assertTrue(json.contains("\"counts\":[1,2,3]"));
        assertTrue(json.contains("\"names\":[\"x\",\"y\",\"z\"]"));

        PojoWithArray read = ADAPTER.readValue(json, PojoWithArray.class);
        assertNotNull(read);
        assertArrayEquals(new int[]{1, 2, 3}, read.counts);
        assertArrayEquals(new double[]{1.1, 2.2, 3.3}, read.ratios, 1e-9);
        assertArrayEquals(new String[]{"x", "y", "z"}, read.names);
    }

    @Test
    void array_empty() {
        PojoWithArray read = ADAPTER.readValue(
            "{\"label\":\"e\",\"counts\":[],\"ratios\":[],\"names\":[]}",
            PojoWithArray.class);
        assertArrayEquals(new int[0], read.counts);
        assertArrayEquals(new double[0], read.ratios);
        assertArrayEquals(new String[0], read.names);
    }

    @Test
    void map_round_trip() {
        PojoWithMap original = new PojoWithMap(
            "maps",
            new LinkedHashMap<>(Map.of("hits", 10, "misses", 3)),
            new LinkedHashMap<>(Map.of("env", "prod", "region", "eu-west")),
            new LinkedHashMap<>(Map.of("alice", new SimplePojo(1, "alice"), "bob", new SimplePojo(2, "bob")))
        );

        String json = ADAPTER.writeValue(original);
        assertTrue(json.contains("\"label\":\"maps\""));

        String formattedJson = new JsonFormatter(Path.of("nothing"), 2).formatToString(ByteBuffer.wrap(json.getBytes(StandardCharsets.UTF_8)));
        System.out.println(formattedJson);

        PojoWithMap read = ADAPTER.readValue(json, PojoWithMap.class);
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
        PojoWithMap read = ADAPTER.readValue(
            "{\"label\":\"e\",\"counters\":{},\"metadata\":{},\"index\":{}}",
            PojoWithMap.class);
        assertNotNull(read.counters);
        assertTrue(read.counters.isEmpty());
        assertTrue(read.metadata.isEmpty());
        assertTrue(read.index.isEmpty());
    }

    @Test
    void queue_round_trip() {
        Queue<String>  tasks      = new ArrayDeque<>(List.of("build", "test", "deploy"));
        Queue<Integer> priorities = new ArrayDeque<>(List.of(1, 2, 3));
        PojoWithQueue original = new PojoWithQueue("q", tasks, priorities);

        String json = ADAPTER.writeValue(original);
        assertTrue(json.contains("\"tasks\":[\"build\",\"test\",\"deploy\"]"));

        PojoWithQueue read = ADAPTER.readValue(json, PojoWithQueue.class);
        assertNotNull(read);
        assertEquals("q", read.label);
        assertEquals(List.of("build", "test", "deploy"), new ArrayList<>(read.tasks));
        assertEquals(List.of(1, 2, 3), new ArrayList<>(read.priorities));
    }

    @Test
    void queue_empty() {
        PojoWithQueue read = ADAPTER.readValue(
            "{\"label\":\"e\",\"tasks\":[],\"priorities\":[]}",
            PojoWithQueue.class);
        assertNotNull(read.tasks);
        assertTrue(read.tasks.isEmpty());
        assertTrue(read.priorities.isEmpty());
    }
}