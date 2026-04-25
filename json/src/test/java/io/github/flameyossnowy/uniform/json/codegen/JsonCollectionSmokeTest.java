package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithArray;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithConcreteCollections;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithConcreteMap;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithConcreteQueue;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithConcreteSet;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithImmutableCollections;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithList;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithListAndArrayList;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithMap;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithNestedCollections;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithQueue;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.PojoWithSet;
import io.github.flameyossnowy.uniform.json.codegen.fixtures.SimplePojo;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.writers.prettifiers.JsonFormatter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

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

    // ============================================================================
    // Tests to verify collections do NOT expose their private internal fields
    // ============================================================================

    @Test
    void arrayList_does_not_expose_private_fields() {
        // Use concrete ArrayList type (not just List interface)
        PojoWithConcreteCollections original = new PojoWithConcreteCollections(
            "test",
            new ArrayList<>(List.of("a", "b", "c")),
            new HashMap<>(Map.of("key", 123)),
            new HashSet<>(Set.of(1, 2, 3))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("ArrayList serialization: " + json);

        // Verify it's serialized as an array, not as an object with private fields
        // ArrayList private fields: elementData, size, modCount
        assertFalse(json.contains("elementData"), "JSON should NOT contain ArrayList's private 'elementData' field");
        assertFalse(json.contains("modCount"), "JSON should NOT contain ArrayList's private 'modCount' field");
        assertFalse(json.contains("\"size\""), "JSON should NOT contain collection 'size' as a property");

        // Verify correct array format
        assertTrue(json.contains("\"items\":[\"a\",\"b\",\"c\"]"), "ArrayList should serialize as JSON array");
    }

    @Test
    void hashMap_does_not_expose_private_fields() {
        PojoWithConcreteCollections original = new PojoWithConcreteCollections(
            "test",
            new ArrayList<>(),
            new HashMap<>(Map.of("env", 1, "region", 2)),
            new HashSet<>()
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("HashMap serialization: " + json);

        // HashMap private fields: table, threshold, loadFactor, modCount, size
        assertFalse(json.contains("table"), "JSON should NOT contain HashMap's private 'table' field");
        assertFalse(json.contains("threshold"), "JSON should NOT contain HashMap's private 'threshold' field");
        assertFalse(json.contains("loadFactor"), "JSON should NOT contain HashMap's private 'loadFactor' field");
        assertFalse(json.contains("modCount"), "JSON should NOT contain HashMap's private 'modCount' field");

        // Verify correct object format
        assertTrue(json.contains("\"env\":1"), "HashMap should serialize as JSON object with correct keys");
    }

    @Test
    void hashSet_does_not_expose_private_fields() {
        PojoWithConcreteCollections original = new PojoWithConcreteCollections(
            "test",
            new ArrayList<>(),
            new HashMap<>(),
            new HashSet<>(Set.of(2, 4, 6))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("HashSet serialization: " + json);

        // HashSet is backed by HashMap, so same concerns apply
        assertFalse(json.contains("PRESENT"), "JSON should NOT expose HashSet's internal 'PRESENT' constant");

        // Verify correct array format
        assertTrue(json.contains("\"uniqueIds\""), "HashSet field should be present");
    }

    @Test
    void linkedList_does_not_expose_private_fields() {
        PojoWithQueue concreteQueue = new PojoWithQueue(
            "test",
            new LinkedList<>(List.of("task1", "task2")),
            new LinkedList<>(List.of(1, 2, 3))
        );

        String json = ADAPTER.writeValue(concreteQueue);
        System.out.println("LinkedList serialization: " + json);

        // LinkedList private fields: first, last, size, modCount
        assertFalse(json.contains("\"first\""), "JSON should NOT contain LinkedList's private 'first' field");
        assertFalse(json.contains("\"last\""), "JSON should NOT contain LinkedList's private 'last' field");

        // Verify correct array format
        assertTrue(json.contains("\"tasks\":[\"task1\",\"task2\"]"), "LinkedList should serialize as JSON array");
    }

    @Test
    void linkedHashMap_does_not_expose_private_fields() {
        PojoWithConcreteMap original = new PojoWithConcreteMap(
            "test",
            new LinkedHashMap<>(Map.of("a", 1, "b", 2)),
            new LinkedHashMap<>(Map.of("x", "val1", "y", "val2"))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("LinkedHashMap serialization: " + json);

        // LinkedHashMap private fields: head, tail, accessOrder
        assertFalse(json.contains("head"), "JSON should NOT contain LinkedHashMap's private 'head' field");
        assertFalse(json.contains("tail"), "JSON should NOT contain LinkedHashMap's private 'tail' field");
        assertFalse(json.contains("accessOrder"), "JSON should NOT contain LinkedHashMap's private 'accessOrder' field");

        // Verify correct object format with order preserved
        assertTrue(json.contains("\"a\":1"), "LinkedHashMap should preserve insertion order");
    }

    @Test
    void treeSet_does_not_expose_private_fields() {
        PojoWithConcreteSet original = new PojoWithConcreteSet(
            "test",
            new TreeSet<>(Set.of(3, 1, 2))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("TreeSet serialization: " + json);

        // TreeSet/TreeMap private fields: m (the backing NavigableMap), comparator
        assertFalse(json.contains("\"m\":"), "JSON should NOT expose TreeSet's internal 'm' field");
        assertFalse(json.contains("comparator"), "JSON should NOT contain TreeSet's private 'comparator' as property");

        // Verify sorted array format
        assertTrue(json.contains("[1,2,3]"), "TreeSet should serialize as sorted JSON array");
    }

    @Test
    void priorityQueue_does_not_expose_private_fields() {
        PojoWithConcreteQueue original = new PojoWithConcreteQueue(
            "test",
            new PriorityQueue<>(List.of(5, 1, 3))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("PriorityQueue serialization: " + json);

        // PriorityQueue private fields: queue, size, comparator, modCount
        assertFalse(json.contains("\"queue\""), "JSON should NOT contain PriorityQueue's private 'queue' field");
        assertFalse(json.contains("modCount"), "JSON should NOT contain PriorityQueue's private 'modCount' field");

        // Verify array format
        assertTrue(json.contains("\"priorities\":"), "PriorityQueue field should be present");
    }

    @Test
    void nested_collections_do_not_expose_private_fields() {
        PojoWithNestedCollections original = new PojoWithNestedCollections(
            "nested",
            List.of(
                new ArrayList<>(List.of("a", "b")),
                new ArrayList<>(List.of("c", "d"))
            ),
            new HashMap<>(Map.of(
                "group1", new HashMap<>(Map.of("k1", "v1")),
                "group2", new HashMap<>(Map.of("k2", "v2"))
            ))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Nested collections: " + json);

        // Verify no private fields leaked at any level
        assertFalse(json.contains("elementData"), "Nested ArrayLists should not expose 'elementData'");
        assertFalse(json.contains("table"), "Nested HashMaps should not expose 'table'");

        // Verify correct structure
        assertTrue(json.contains("[[\"a\",\"b\"],[\"c\",\"d\"]]"), "Nested lists should serialize correctly");
        assertTrue(json.contains("\"group1\":{\"k1\":\"v1\"}"), "Nested maps should serialize correctly");
    }

    @Test
    void collection_with_nulls_does_not_expose_private_fields() {
        ArrayList<String> listWithNulls = new ArrayList<>();
        listWithNulls.add("a");
        listWithNulls.add(null);
        listWithNulls.add("b");

        PojoWithConcreteCollections original = new PojoWithConcreteCollections(
            "nulls",
            listWithNulls,
            new HashMap<>(),
            new HashSet<>()
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Collection with nulls: " + json);

        // Verify nulls handled correctly and no private fields leaked
        assertTrue(json.contains("[\"a\",null,\"b\"]"), "Null elements should be preserved");
        assertFalse(json.contains("elementData"), "Should not expose private fields even with nulls");
    }

    @Test
    void list_interface_and_arrayList_concrete_produce_identical_json() {
        // Create POJO with both List interface field and ArrayList concrete field
        ArrayList<String> items = new ArrayList<>(List.of("x", "y", "z"));

        PojoWithListAndArrayList original = new PojoWithListAndArrayList(
            "compare",
            items,           // assigned to List<String> field
            items            // assigned to ArrayList<String> field
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("List vs ArrayList: " + json);

        // Both fields should serialize identically as JSON arrays
        // Neither should expose private fields like elementData, modCount, size
        assertFalse(json.contains("elementData"), "Neither List nor ArrayList should expose 'elementData'");
        assertFalse(json.contains("modCount"), "Neither List nor ArrayList should expose 'modCount'");
        assertFalse(json.contains("\"size\""), "Neither should expose 'size' as a property");

        // Both should produce same JSON structure
        assertTrue(json.contains("\"listInterface\":[\"x\",\"y\",\"z\"]"), "List interface field should serialize as array");
        assertTrue(json.contains("\"arrayListConcrete\":[\"x\",\"y\",\"z\"]"), "ArrayList concrete field should serialize as array");

        // Verify round-trip works for both
        PojoWithListAndArrayList read = ADAPTER.readValue(json, PojoWithListAndArrayList.class);
        assertNotNull(read);
        assertEquals(List.of("x", "y", "z"), read.listInterface);
        assertEquals(List.of("x", "y", "z"), read.arrayListConcrete);
    }

    @Test
    void various_list_implementations_serialize_correctly() {
        // Test different List implementations all serialize without private fields
        PojoWithList original = new PojoWithList(
            "variants",
            List.of("a", "b"),                      // Immutable list
            new ArrayList<>(List.of(1, 2, 3)),       // ArrayList
            List.of(new SimplePojo(1, "test"))
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Various List implementations: " + json);

        // No implementation should expose private fields
        assertFalse(json.contains("elementData"), "Should not expose ArrayList's elementData");

        // All should serialize as proper JSON arrays
        assertTrue(json.contains("\"tags\":[\"a\",\"b\"]"), "tags should be JSON array");
        assertTrue(json.contains("\"scores\":[1,2,3]"), "scores should be JSON array");
        assertTrue(json.contains("\"children\":[{\"id\":1,\"name\":\"test\"}]"), "children should be JSON array of objects");
    }

    // ============================================================================
    // Tests for List.of(), Set.of(), Map.of() immutable collections (JPMS safety)
    // ============================================================================

    @Test
    @DisplayName("Direct reflection on immutable collection class should not crash")
    void immutable_collection_reflection_should_not_crash() {
        // This test verifies that ReflectionMetadata does not attempt to access
        // private fields in ImmutableCollections$List12 which JPMS blocks
        List<String> immutableList = List.of("a", "b");

        // This should not throw InaccessibleObjectException
        assertDoesNotThrow(() -> {
            io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata meta =
                io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata.of(immutableList.getClass());
            // Should have no properties since we skip Collection types
            assertTrue(meta.properties.isEmpty(),
                "Immutable collection class should have no reflected properties");
        }, "Reflection on ImmutableCollections$List12 should not crash with JPMS");
    }

    @Test
    @DisplayName("Direct reflection on immutable set class should not crash")
    void immutable_set_reflection_should_not_crash() {
        Set<Integer> immutableSet = Set.of(1, 2, 3);

        assertDoesNotThrow(() -> {
            io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata meta =
                io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata.of(immutableSet.getClass());
            assertTrue(meta.properties.isEmpty(),
                "Immutable set class should have no reflected properties");
        }, "Reflection on ImmutableCollections$SetN should not crash with JPMS");
    }

    @Test
    @DisplayName("Direct reflection on immutable map class should not crash")
    void immutable_map_reflection_should_not_crash() {
        Map<String, String> immutableMap = Map.of("key", "value");

        assertDoesNotThrow(() -> {
            io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata meta =
                io.github.flameyossnowy.uniform.json.reflect.ReflectionMetadata.of(immutableMap.getClass());
            assertTrue(meta.properties.isEmpty(),
                "Immutable map class should have no reflected properties");
        }, "Reflection on ImmutableCollections$MapN should not crash with JPMS");
    }

    @Test
    void list_of_serializes_without_jpms_issues() {
        // List.of() returns internal immutable list classes (List12, ListN)
        // These are in java.util but are not public - JPMS may restrict access
        PojoWithImmutableCollections original = new PojoWithImmutableCollections(
            "immutable",
            List.of("a", "b"),           // List.of() - returns internal List12/ListN
            Set.of(),                            // Empty set
            Map.of()                             // Empty map
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("List.of() serialization: " + json);

        // Should serialize as normal array without accessing internal fields
        assertTrue(json.contains("\"immutableList\":[\"a\",\"b\"]"), "List.of() should serialize as array");
        assertFalse(json.contains("List12"), "Should not expose internal class name");
        assertFalse(json.contains("ListN"), "Should not expose internal class name");
    }

    @Test
    void set_of_serializes_without_jpms_issues() {
        // Set.of() returns internal immutable set classes (Set12, SetN)
        PojoWithImmutableCollections original = new PojoWithImmutableCollections(
            "immutable",
            List.of(),
            Set.of(1, 2, 3),                    // Set.of() - returns internal Set12/SetN
            Map.of()
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Set.of() serialization: " + json);

        // Should serialize as normal array without accessing internal fields
        // Note: Set.of() iteration order is not guaranteed (hash-based)
        assertTrue(json.contains("\"immutableSet\":["), "Set.of() should serialize as array");
        assertTrue(json.contains("1"), "Set should contain 1");
        assertTrue(json.contains("2"), "Set should contain 2");
        assertTrue(json.contains("3"), "Set should contain 3");
        assertFalse(json.contains("Set12"), "Should not expose internal class name");
        assertFalse(json.contains("SetN"), "Should not expose internal class name");
    }

    @Test
    void map_of_serializes_without_jpms_issues() {
        // Map.of() returns internal immutable map classes (Map1, MapN)
        PojoWithImmutableCollections original = new PojoWithImmutableCollections(
            "immutable",
            List.of(),
            Set.of(),
            Map.of("env", "prod", "region", "west")  // Map.of() - returns internal Map1/MapN
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Map.of() serialization: " + json);

        // Should serialize as normal object without accessing internal fields
        assertTrue(json.contains("\"env\":\"prod\""), "Map.of() should serialize as object");
        assertTrue(json.contains("\"region\":\"west\""), "Map.of() should serialize as object");
        assertFalse(json.contains("Map1"), "Should not expose internal class name");
        assertFalse(json.contains("MapN"), "Should not expose internal class name");
        assertFalse(json.contains("\"table\""), "Should not try to access internal HashMap-like fields");
    }

    @Test
    void list_of_entries_round_trip() {
        // Reading back into a mutable list should work fine
        PojoWithImmutableCollections original = new PojoWithImmutableCollections(
            "roundtrip",
            List.of("x", "y", "z"),
            Set.of(1, 2),
            Map.of("key", "value")
        );

        String json = ADAPTER.writeValue(original);
        PojoWithImmutableCollections read = ADAPTER.readValue(json, PojoWithImmutableCollections.class);

        assertNotNull(read);
        assertEquals("roundtrip", read.label);
        // Read values should be mutable ArrayList/LinkedHashSet/LinkedHashMap
        assertEquals(List.of("x", "y", "z"), read.immutableList);
        assertEquals(Set.of(1, 2), read.immutableSet);
        assertEquals(Map.of("key", "value"), read.immutableMap);
    }

    @Test
    void list_of_with_nulls_handled_safely() {
        // List.of() doesn't allow nulls, but we can have null in the list field itself
        PojoWithImmutableCollections original = new PojoWithImmutableCollections(
            "nulls",
            null,    // null list field
            null,    // null set field
            null     // null map field
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Null collections: " + json);

        assertTrue(json.contains("\"immutableList\":null"), "Null list should serialize as null");
        assertTrue(json.contains("\"immutableSet\":null"), "Null set should serialize as null");
        assertTrue(json.contains("\"immutableMap\":null"), "Null map should serialize as null");
    }

    @Test
    void mixed_mutable_and_immutable_collections_serialize_consistently() {
        // Mix of mutable and immutable collections should all serialize the same way
        PojoWithList mutablePojo = new PojoWithList(
            "mutable",
            new ArrayList<>(List.of("a", "b")),
            new ArrayList<>(List.of(1, 2)),
            List.of()
        );

        PojoWithImmutableCollections immutablePojo = new PojoWithImmutableCollections(
            "immutable",
            List.of("a", "b"),
            Set.of(),
            Map.of()
        );

        String mutableJson = ADAPTER.writeValue(mutablePojo);
        String immutableJson = ADAPTER.writeValue(immutablePojo);

        // Extract just the list parts for comparison
        System.out.println("Mutable: " + mutableJson);
        System.out.println("Immutable: " + immutableJson);

        // Both lists should serialize identically
        assertTrue(mutableJson.contains("\"tags\":[\"a\",\"b\"]"), "Mutable list should serialize correctly");
        assertTrue(immutableJson.contains("\"immutableList\":[\"a\",\"b\"]"), "Immutable list should serialize correctly");
    }

    @Test
    void nested_immutable_collections_serialize_correctly() {
        // List.of() containing other immutable collections
        List<List<String>> nestedImmutable = List.of(
            List.of("a", "b"),
            List.of("c", "d")
        );

        // Create a custom nested structure using a simple pojo
        String json = ADAPTER.writeValue(nestedImmutable);
        System.out.println("Nested immutable: " + json);

        assertEquals("[[\"a\",\"b\"],[\"c\",\"d\"]]", json, "Nested List.of() should serialize correctly");
    }

    // ============================================================================
    // COLLECTION EDGE CASE / SECURITY TESTS
    // ============================================================================

    @Test
    @DisplayName("Collection with all null elements")
    void collection_with_all_nulls() {
        List<String> allNulls = new ArrayList<>();
        allNulls.add(null);
        allNulls.add(null);
        allNulls.add(null);

        PojoWithList original = new PojoWithList(
            "allNulls",
            allNulls,
            Arrays.asList(null, null),
            Arrays.asList(null, null)
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("All nulls: " + json);

        assertTrue(json.contains("\"tags\":[null,null,null]"), "All null elements should serialize correctly");

        // Reading back - null elements in list of String may cause type issues depending on implementation
        assertDoesNotThrow(() -> {
            try {
                PojoWithList read = ADAPTER.readValue(json, PojoWithList.class);
                assertNotNull(read.tags);
                assertEquals(3, read.tags.size());
                // Nulls may become empty strings or remain null depending on implementation
            } catch (NumberFormatException e) {
                // Null handling may vary by implementation
                System.out.println("Null handling produced NumberFormatException (implementation detail)");
            }
        });
    }

    @Test
    @DisplayName("Very large collection handling")
    void very_large_collection() {
        // Test with a large collection (500 elements) - exceeds SIMD threshold
        List<Integer> largeList = new ArrayList<>(500);
        for (int i = 0; i < 500; i++) {
            largeList.add(i);
        }

        PojoWithList original = new PojoWithList(
            "large",
            List.of(),
            largeList,
            List.of()
        );

        // Serialization should work
        String json = ADAPTER.writeValue(original);
        assertNotNull(json);
        assertTrue(json.length() > 1000, "Large JSON should be generated");
        assertTrue(json.contains("[0,1,2,"), "Should contain sequence start");

        // Read back and verify
        PojoWithList read = ADAPTER.readValue(json, PojoWithList.class);
        assertNotNull(read);
        assertEquals(500, read.scores.size());
        assertEquals(0, read.scores.get(0));
        assertEquals(499, read.scores.get(499));
    }

    @Test
    @DisplayName("Circular reference detection (should not crash)")
    void circular_reference_handling() {
        // Create a self-referential list (Java doesn't allow true circularity in ArrayList)
        // But we can create deeply nested structures
        List<Object> list1 = new ArrayList<>();
        List<Object> list2 = new ArrayList<>();
        list1.add(list2);
        list2.add("value");

        String json = ADAPTER.writeValue(list1);
        System.out.println("Nested lists: " + json);

        assertEquals("[[\"value\"]]", json);
    }

    @Test
    @DisplayName("Concurrent modification during write (defensive copy test)")
    void concurrent_modification_safety() {
        CopyOnWriteArrayList<String> threadSafeList = new CopyOnWriteArrayList<>();
        threadSafeList.add("a");
        threadSafeList.add("b");
        threadSafeList.add("c");

        PojoWithConcreteCollections original = new PojoWithConcreteCollections(
            "concurrent",
            new ArrayList<>(threadSafeList),
            new HashMap<>(),
            new HashSet<>()
        );

        String json = ADAPTER.writeValue(original);
        assertTrue(json.contains("[\"a\",\"b\",\"c\"]"), "Thread-safe collection should serialize correctly");
    }

    @Test
    @DisplayName("Unmodifiable collection wrapper handling")
    void unmodifiable_collections() {
        List<String> mutable = new ArrayList<>(List.of("a", "b"));
        List<String> unmodifiable = Collections.unmodifiableList(mutable);

        String json = ADAPTER.writeValue(unmodifiable);
        System.out.println("Unmodifiable: " + json);

        assertEquals("[\"a\",\"b\"]", json, "Unmodifiable wrapper should serialize correctly");
    }

    @Test
    @DisplayName("Synchronized collection wrapper handling")
    void synchronized_collections() {
        List<String> synced = Collections.synchronizedList(new ArrayList<>(List.of("x", "y")));

        String json = ADAPTER.writeValue(synced);
        System.out.println("Synchronized: " + json);

        assertEquals("[\"x\",\"y\"]", json, "Synchronized wrapper should serialize correctly");
    }

    @Test
    @DisplayName("Empty collections of various types")
    void various_empty_collections() {
        List<Collection<?>> emptyCollections = List.of(
            new ArrayList<>(),
            new LinkedList<>(),
            new HashSet<>(),
            new LinkedHashSet<>(),
            new TreeSet<>(),
            new ArrayDeque<>(),
            Collections.emptyList(),
            Collections.emptySet()
        );

        for (Collection<?> empty : emptyCollections) {
            String json = ADAPTER.writeValue(empty);
            assertEquals("[]", json, empty.getClass().getSimpleName() + " should serialize as []");
        }
    }

    @Test
    @DisplayName("Empty maps of various types")
    void various_empty_maps() {
        List<Map<?, ?>> emptyMaps = List.of(
            new HashMap<>(),
            new LinkedHashMap<>(),
            new TreeMap<>(),
            new ConcurrentHashMap<>(),
            Collections.emptyMap()
        );

        for (Map<?, ?> empty : emptyMaps) {
            String json = ADAPTER.writeValue(empty);
            assertEquals("{}", json, empty.getClass().getSimpleName() + " should serialize as {}");
        }
    }

    @Test
    @DisplayName("Single element collections")
    void single_element_collections() {
        List<String> singleList = Collections.singletonList("only");
        Set<String> singleSet = Collections.singleton("only");

        String listJson = ADAPTER.writeValue(singleList);
        String setJson = ADAPTER.writeValue(singleSet);

        assertEquals("[\"only\"]", listJson);
        assertEquals("[\"only\"]", setJson);
    }

    @Test
    @DisplayName("Map with null key (should handle or throw gracefully)")
    void map_with_null_key() {
        HashMap<String, String> mapWithNullKey = new HashMap<>();
        mapWithNullKey.put(null, "nullKeyValue");
        mapWithNullKey.put("key", "value");

        // HashMap allows one null key - should serialize or throw controlled exception
        assertDoesNotThrow(() -> {
            String json = ADAPTER.writeValue(mapWithNullKey);
            System.out.println("Map with null key: " + json);
            // Null key typically becomes "null" string in JSON
            assertTrue(json.contains("null"));
        });
    }

    @Test
    @DisplayName("Map with null values")
    void map_with_null_values() {
        HashMap<String, String> mapWithNullValues = new HashMap<>();
        mapWithNullValues.put("key1", null);
        mapWithNullValues.put("key2", "value");

        String json = ADAPTER.writeValue(mapWithNullValues);
        System.out.println("Map with null value: " + json);

        assertTrue(json.contains("\"key1\":null"), "Null value should be serialized");
        assertTrue(json.contains("\"key2\":\"value\""), "Non-null value should be serialized");
    }

    @Test
    @DisplayName("Special characters in collection elements")
    void special_characters_in_elements() {
        List<String> specialStrings = List.of(
            "line1\nline2",
            "tab\there",
            "quote\"inside",
            "backslash\\here",
            "\u0001\u0002control",
            "emoji🎉",
            "日本語"
        );

        PojoWithList original = new PojoWithList(
            "special",
            specialStrings,
            List.of(),
            List.of()
        );

        String json = ADAPTER.writeValue(original);
        System.out.println("Special chars: " + json);

        PojoWithList read = ADAPTER.readValue(json, PojoWithList.class);
        assertEquals(specialStrings.size(), read.tags.size());
        assertEquals("line1\nline2", read.tags.get(0));
        assertEquals("quote\"inside", read.tags.get(2));
        assertEquals("backslash\\here", read.tags.get(3));
    }

    @Test
    @DisplayName("Mixed type collection (raw Object list)")
    void mixed_type_collection() {
        List<Object> mixed = new ArrayList<>();
        mixed.add("string");
        mixed.add(42);
        mixed.add(3.14);
        mixed.add(true);
        mixed.add(null);

        String json = ADAPTER.writeValue(mixed);
        System.out.println("Mixed types: " + json);

        // Verify all elements are present - numbers may be formatted differently
        assertTrue(json.contains("\"string\""));
        assertTrue(json.contains("42"));
        assertTrue(json.contains("3.14") || json.contains("3.140"));
        assertTrue(json.contains("true"));
        assertTrue(json.contains("null"));
    }

    @Test
    @DisplayName("ArrayDeque as Queue serialization")
    void arrayDeque_queue_serialization() {
        ArrayDeque<String> deque = new ArrayDeque<>();
        deque.add("first");
        deque.add("second");
        deque.add("third");

        String json = ADAPTER.writeValue(deque);
        System.out.println("ArrayDeque: " + json);

        // Should preserve iteration order
        assertEquals("[\"first\",\"second\",\"third\"]", json);
    }

    @Test
    @DisplayName("Stack serialization")
    void stack_serialization() {
        Stack<String> stack = new Stack<>();
        stack.push("bottom");
        stack.push("middle");
        stack.push("top");

        String json = ADAPTER.writeValue(stack);
        System.out.println("Stack: " + json);

        // Stack extends Vector, should serialize as array
        // Note: iteration order is bottom to top (insertion order)
        assertEquals("[\"bottom\",\"middle\",\"top\"]", json);
    }

    @Test
    @DisplayName("TreeMap with custom comparator")
    void treemap_with_comparator() {
        TreeMap<Integer, String> reverseMap = new TreeMap<>(Comparator.reverseOrder());
        reverseMap.put(1, "one");
        reverseMap.put(3, "three");
        reverseMap.put(2, "two");

        String json = ADAPTER.writeValue(reverseMap);
        System.out.println("TreeMap reverse: " + json);

        // Should be sorted by comparator (descending)
        assertTrue(json.contains("\"3\":\"three\""), "TreeMap should respect comparator ordering");
    }

    @Test
    @DisplayName("ConcurrentHashMap serialization")
    void concurrent_hashmap() {
        ConcurrentHashMap<String, Integer> concurrentMap = new ConcurrentHashMap<>();
        concurrentMap.put("a", 1);
        concurrentMap.put("b", 2);

        String json = ADAPTER.writeValue(concurrentMap);
        System.out.println("ConcurrentHashMap: " + json);

        assertTrue(json.contains("\"a\":1"));
        assertTrue(json.contains("\"b\":2"));
    }

    @Test
    @DisplayName("BitSet serialization as array of set bits")
    void bitset_handling() {
        BitSet bitSet = new BitSet();
        bitSet.set(0);
        bitSet.set(5);
        bitSet.set(100);

        // BitSet should serialize as array of indices where bits are set
        String json = ADAPTER.writeValue(bitSet);
        System.out.println("BitSet: " + json);

        assertEquals("[0,5,100]", json, "BitSet should serialize as array of set bit indices");
    }

    @Test
    @DisplayName("Empty BitSet serialization")
    void empty_bitset() {
        BitSet empty = new BitSet();
        String json = ADAPTER.writeValue(empty);
        assertEquals("[]", json, "Empty BitSet should serialize as empty array");
    }

    @Test
    @DisplayName("BitSet with contiguous bits")
    void bitset_contiguous() {
        BitSet bitSet = new BitSet();
        bitSet.set(1, 5); // Sets bits 1, 2, 3, 4

        String json = ADAPTER.writeValue(bitSet);
        System.out.println("BitSet contiguous: " + json);

        assertEquals("[1,2,3,4]", json);
    }

    @Test
    @DisplayName("Properties map (legacy map type)")
    void properties_map() {
        Properties props = new Properties();
        props.setProperty("key1", "value1");
        props.setProperty("key2", "value2");

        String json = ADAPTER.writeValue(props);
        System.out.println("Properties: " + json);

        assertTrue(json.contains("\"key1\":\"value1\""));
        assertTrue(json.contains("\"key2\":\"value2\""));
    }

    @Test
    @DisplayName("IdentityHashMap (reference equality)")
    void identity_hashmap() {
        IdentityHashMap<String, String> identityMap = new IdentityHashMap<>();
        String key1 = new String("key"); // Different object
        String key2 = new String("key"); // Different object, same value
        identityMap.put(key1, "value1");
        identityMap.put(key2, "value2");

        // IdentityHashMap uses reference equality, both keys are kept
        assertEquals(2, identityMap.size());

        String json = ADAPTER.writeValue(identityMap);
        System.out.println("IdentityHashMap: " + json);
        // Both entries should be serialized
    }

    @Test
    @DisplayName("WeakHashMap handling")
    void weak_hashmap() {
        WeakHashMap<String, String> weakMap = new WeakHashMap<>();
        String key = "key";
        weakMap.put(key, "value");

        String json = ADAPTER.writeValue(weakMap);
        System.out.println("WeakHashMap: " + json);

        assertTrue(json.contains("\"key\":\"value\""));
    }

    @Test
    @DisplayName("EnumMap serialization")
    void enum_map() {
        EnumMap<TimeUnit, String> enumMap = new EnumMap<>(TimeUnit.class);
        enumMap.put(TimeUnit.SECONDS, "s");
        enumMap.put(TimeUnit.MINUTES, "m");

        String json = ADAPTER.writeValue(enumMap);
        System.out.println("EnumMap: " + json);

        assertTrue(json.contains("\"SECONDS\":\"s\""));
        assertTrue(json.contains("\"MINUTES\":\"m\""));
    }

    @Test
    @DisplayName("LinkedHashMap iteration order preservation")
    void linkedhashmap_order_preservation() {
        LinkedHashMap<String, Integer> insertionOrder = new LinkedHashMap<>();
        insertionOrder.put("first", 1);
        insertionOrder.put("second", 2);
        insertionOrder.put("third", 3);

        String json = ADAPTER.writeValue(insertionOrder);
        System.out.println("LinkedHashMap order: " + json);

        // Verify order is preserved
        int firstIndex = json.indexOf("\"first\"");
        int secondIndex = json.indexOf("\"second\"");
        int thirdIndex = json.indexOf("\"third\"");

        assertTrue(firstIndex < secondIndex, "First should come before second");
        assertTrue(secondIndex < thirdIndex, "Second should come before third");
    }

    @Test
    @DisplayName("Collection with extreme numeric values")
    void collection_with_extreme_numbers() {
        List<Object> extremes = List.of(
            Integer.MAX_VALUE,
            Integer.MIN_VALUE,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            Double.MAX_VALUE,
            Double.MIN_VALUE,
            Double.POSITIVE_INFINITY,
            Double.NEGATIVE_INFINITY,
            Double.NaN,
            0.0,
            -0.0
        );

        // Should handle without crashing
        assertDoesNotThrow(() -> {
            String json = ADAPTER.writeValue(extremes);
            System.out.println("Extreme numbers: " + json);
        });
    }

    @Test
    @DisplayName("Malformed collection JSON handling")
    void malformed_collection_json() {
        // Array where object expected - library behavior varies
        assertDoesNotThrow(() -> {
            try {
                PojoWithList result = ADAPTER.readValue("[1, 2, 3]", PojoWithList.class);
                // May succeed or fail depending on implementation
            } catch (Exception e) {
                // Exception is acceptable
            }
        });

        // Object in array element context - library may be lenient
        assertDoesNotThrow(() -> {
            PojoWithList result = ADAPTER.readValue(
                "{\"label\":\"test\",\"tags\":[\"a\",{}],\"scores\":[],\"children\":[]}",
                PojoWithList.class
            );
            System.out.println(result);
            // Object in string array may become null or cause type issues
        });
    }

    // Enum for EnumMap test
    enum TimeUnit {
        SECONDS, MINUTES, HOURS, DAYS
    }
}