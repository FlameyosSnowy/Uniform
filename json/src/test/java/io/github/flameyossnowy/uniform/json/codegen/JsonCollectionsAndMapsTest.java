package io.github.flameyossnowy.uniform.json.codegen;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import io.github.flameyossnowy.uniform.json.JsonAdapter;
import io.github.flameyossnowy.uniform.json.JsonConfig;
import io.github.flameyossnowy.uniform.json.ReflectionConfig;
import io.github.flameyossnowy.uniform.json.exceptions.JsonException;
import io.github.flameyossnowy.uniform.json.features.JsonReadFeature;
import io.github.flameyossnowy.uniform.json.features.JsonWriteFeature;
import io.github.flameyossnowy.uniform.json.mappers.JsonMapperRegistry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

public class JsonCollectionsAndMapsTest {

    private static JsonConfig config() {
        return new JsonConfig(false, 2, EnumSet.noneOf(JsonReadFeature.class),
            EnumSet.noneOf(JsonWriteFeature.class), ReflectionConfig.DEFAULT);
    }

    private static JsonAdapter adapter() {
        return new JsonAdapter(config());
    }

    @Test
    @DisplayName("Set reads as an insertion-ordered, de-duplicated set")
    void registry_set() {
        Set<?> set = adapter().readValue("[3,1,3,2]", Set.class);
        assertInstanceOf(LinkedHashSet.class, set);
        assertEquals(List.of(3, 1, 2), new ArrayList<>(set));
    }

    @Test
    @DisplayName("SortedSet reads as a TreeSet")
    void registry_sorted_set() {
        SortedSet<?> set = adapter().readValue("[3,1,2]", SortedSet.class);
        assertInstanceOf(TreeSet.class, set);
        assertEquals(1, set.first());
    }

    @Test
    @DisplayName("Queue and Deque are null-tolerant")
    void registry_queue_and_deque() {
        Queue<?> queue = adapter().readValue("[1,null,2]", Queue.class);
        assertEquals(Arrays.asList(1, null, 2), new ArrayList<>(queue));

        Deque<?> deque = adapter().readValue("[1,2,3]", Deque.class);
        assertEquals(1, deque.peekFirst());
        assertEquals(3, deque.peekLast());
    }

    @Test
    @DisplayName("Plain Collection reads as a list")
    void registry_collection() {
        Collection<?> c = adapter().readValue("[1,2]", Collection.class);
        assertInstanceOf(ArrayList.class, c);
        assertEquals(List.of(1, 2), c);
    }

    @Test
    @DisplayName("Concrete collection types are instantiated without reflection")
    void registry_concrete_types() {
        assertInstanceOf(HashSet.class,                 adapter().readValue("[1]", HashSet.class));
        assertInstanceOf(TreeSet.class,                 adapter().readValue("[2,1]", TreeSet.class));
        assertInstanceOf(ArrayDeque.class,              adapter().readValue("[1]", ArrayDeque.class));
        assertInstanceOf(ConcurrentLinkedQueue.class,   adapter().readValue("[1]", ConcurrentLinkedQueue.class));
        assertInstanceOf(CopyOnWriteArrayList.class,    adapter().readValue("[1]", CopyOnWriteArrayList.class));

        PriorityQueue<?> pq = adapter().readValue("[5,1,3]", PriorityQueue.class);
        assertEquals(1, pq.poll());
    }

    public static class UnregisteredList extends ArrayList<Object> {}
    public static class RegisteredList extends ArrayList<Object> {}

    @Test
    @DisplayName("Unknown collection class fails cleanly until a factory is registered")
    void registry_custom_collection_requires_factory() {
        assertThrows(JsonException.class,
            () -> adapter().readValue("[1]", UnregisteredList.class));

        JsonMapperRegistry.registerCollectionFactory(RegisteredList.class, RegisteredList::new);
        RegisteredList list = adapter().readValue("[1,2]", RegisteredList.class);
        assertEquals(List.of(1, 2), list);
    }

    @Test
    @DisplayName("BitSet read")
    void bitset_read() {
        BitSet bits = adapter().readValue("[0,3,64]", BitSet.class);
        assertTrue(bits.get(0));
        assertTrue(bits.get(3));
        assertTrue(bits.get(64));
        assertFalse(bits.get(1));
        assertEquals(3, bits.cardinality());
    }

    @Test
    @DisplayName("BitSet write")
    void bitset_write() {
        BitSet bits = new BitSet();
        bits.set(0);
        bits.set(3);
        bits.set(64);
        assertEquals("[0,3,64]", adapter().writeValue(bits));
        assertEquals("[]", adapter().writeValue(new BitSet()));
    }

    @SerializedObject
    public static class CollectionFields {
        public HashSet<String> hashSet;
        public TreeSet<Integer> treeSet;
        public ArrayList<String> arrayList;
        public LinkedList<String> linkedList;
        public Set<String> set;
        public SortedSet<String> sorted;
        public Deque<String> deque;
        public Queue<String> queue;
        public BitSet bits;

        public CollectionFields() {}
    }

    private static final String COLLECTION_FIELDS_JSON =
        "{\"hashSet\":[\"a\",\"b\"],\"treeSet\":[3,1,2],\"arrayList\":[\"x\",\"y\"],"
            + "\"linkedList\":[\"p\",\"q\"],\"set\":[\"s\",\"s\",\"t\"],\"sorted\":[\"b\",\"a\"],"
            + "\"deque\":[\"d1\",\"d2\"],\"queue\":[\"q1\",\"q2\"],\"bits\":[1,5,64]}";

    @Test
    @DisplayName("Generated reader honors the declared collection type of each field")
    void processor_declared_collection_types() {
        CollectionFields f = adapter().readValue(COLLECTION_FIELDS_JSON, CollectionFields.class);

        assertEquals(Set.of("a", "b"), f.hashSet);
        assertEquals(List.of(1, 2, 3), new ArrayList<>(f.treeSet));
        assertEquals(List.of("x", "y"), f.arrayList);
        assertEquals(List.of("p", "q"), f.linkedList);
        assertEquals(List.of("s", "t"), new ArrayList<>(f.set));
        assertEquals("a", f.sorted.first());
        assertEquals("d1", f.deque.peekFirst());
        assertEquals("q1", f.queue.peek());
        assertTrue(f.bits.get(64));
        assertEquals(3, f.bits.cardinality());
    }

    @Test
    @DisplayName("Generated writer round-trips set, deque, queue and BitSet fields")
    void processor_round_trip() {
        JsonAdapter adapter = adapter();
        CollectionFields original = adapter.readValue(COLLECTION_FIELDS_JSON, CollectionFields.class);

        String json = adapter.writeValue(original);
        assertTrue(json.contains("\"bits\":[1,5,64]"));

        CollectionFields copy = adapter.readValue(json, CollectionFields.class);
        assertEquals(original.hashSet, copy.hashSet);
        assertEquals(original.treeSet, copy.treeSet);
        assertEquals(original.arrayList, copy.arrayList);
        assertEquals(original.linkedList, copy.linkedList);
        assertEquals(original.set, copy.set);
        assertEquals(original.sorted, copy.sorted);
        assertEquals(new ArrayList<>(original.deque), new ArrayList<>(copy.deque));
        assertEquals(new ArrayList<>(original.queue), new ArrayList<>(copy.queue));
        assertEquals(original.bits, copy.bits);
    }

    public enum Color { RED, GREEN }

    public static class Inner {
        public int i;
        public Inner() {}
    }

    public static class Reflective {
        public Map<String, Integer> scores;
        public Map<Integer, String> byId;
        public Map<Long, Boolean> byLong;
        public Map<Color, Integer> byColor;
        public Map<UUID, String> byUuid;
        public TreeMap<String, Integer> sortedMap;
        public Map<String, List<String>> tags;
        public Map<String, Map<String, Long>> nestedMaps;
        public Map<String, double[]> arrays;
        public Map<String, Inner> objects;
        public Map<String, Object> untyped;
        public Map<String, Color> colorValues;
        public Map<String, Set<Integer>> setValues;

        public HashSet<String> hashSet;
        public TreeSet<Integer> treeSet;
        public Deque<Integer> deque;
        public Collection<String> collection;
        public List<List<Integer>> matrix;
        public List<Map<String, Integer>> listOfMaps;
        public List<UUID> uuids;
        public Set<Color> colors;
        public BitSet bits;

        public Reflective() {}
    }

    private static Reflective readReflective(String json) {
        return adapter().readValue(json, Reflective.class);
    }

    @Test
    @DisplayName("Map with primitive values and every common key type")
    void reflection_map_key_and_value_types() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Reflective r = readReflective("{"
            + "\"scores\":{\"a\":1,\"b\":2},"
            + "\"byId\":{\"1\":\"x\",\"2\":\"y\"},"
            + "\"byLong\":{\"9000000000\":true},"
            + "\"byColor\":{\"RED\":1,\"GREEN\":2},"
            + "\"byUuid\":{\"" + id + "\":\"found\"}"
            + "}");

        assertEquals(Map.of("a", 1, "b", 2), r.scores);
        assertEquals(Map.of(1, "x", 2, "y"), r.byId);
        assertEquals(Map.of(9_000_000_000L, true), r.byLong);
        assertEquals(Map.of(Color.RED, 1, Color.GREEN, 2), r.byColor);
        assertEquals(Map.of(id, "found"), r.byUuid);
    }

    @Test
    @DisplayName("Declared map type is respected")
    void reflection_map_declared_type() {
        Reflective r = readReflective("{\"sortedMap\":{\"b\":2,\"a\":1}}");
        assertInstanceOf(TreeMap.class, r.sortedMap);
        assertEquals("a", r.sortedMap.firstKey());
    }

    @Test
    @DisplayName("Map values may be collections, maps, arrays, objects and enums")
    void reflection_map_nested_values() {
        Reflective r = readReflective("{"
            + "\"tags\":{\"k\":[\"a\",\"b\"]},"
            + "\"nestedMaps\":{\"o\":{\"i\":5}},"
            + "\"arrays\":{\"p\":[1.5,2.5]},"
            + "\"objects\":{\"n\":{\"i\":7}},"
            + "\"colorValues\":{\"c\":\"GREEN\"},"
            + "\"setValues\":{\"s\":[3,3,4]}"
            + "}");

        assertEquals(List.of("a", "b"), r.tags.get("k"));
        assertEquals(5L, r.nestedMaps.get("o").get("i"));
        assertArrayEquals(new double[]{1.5, 2.5}, r.arrays.get("p"));
        assertEquals(7, r.objects.get("n").i);
        assertEquals(Color.GREEN, r.colorValues.get("c"));
        assertEquals(Set.of(3, 4), r.setValues.get("s"));
    }

    @Test
    @DisplayName("Map<String, Object> reads untyped values")
    void reflection_map_untyped_values() {
        Reflective r = readReflective("{\"untyped\":{\"a\":1,\"b\":\"x\",\"c\":[1,2],\"d\":{\"e\":true},\"f\":null}}");

        assertEquals(1, r.untyped.get("a"));
        assertEquals("x", r.untyped.get("b"));
        assertEquals(List.of(1, 2), r.untyped.get("c"));
        assertEquals(Map.of("e", true), r.untyped.get("d"));
        assertTrue(r.untyped.containsKey("f"));
        assertNull(r.untyped.get("f"));
    }

    @Test
    @DisplayName("Collections keep their declared type and support nesting")
    void reflection_collections() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        Reflective r = readReflective("{"
            + "\"hashSet\":[\"a\",\"b\"],"
            + "\"treeSet\":[3,1,2],"
            + "\"deque\":[1,2,3],"
            + "\"collection\":[\"x\",\"y\"],"
            + "\"matrix\":[[1,2],[3],[]],"
            + "\"listOfMaps\":[{\"a\":1},{\"b\":2}],"
            + "\"uuids\":[\"" + id + "\"],"
            + "\"colors\":[\"RED\",\"GREEN\"],"
            + "\"bits\":[2,4]"
            + "}");

        assertInstanceOf(HashSet.class, r.hashSet);
        assertEquals(Set.of("a", "b"), r.hashSet);
        assertEquals(List.of(1, 2, 3), new ArrayList<>(r.treeSet));
        assertEquals(1, r.deque.peekFirst());
        assertEquals(List.of("x", "y"), new ArrayList<>(r.collection));
        assertEquals(List.of(List.of(1, 2), List.of(3), List.of()), r.matrix);
        assertEquals(List.of(Map.of("a", 1), Map.of("b", 2)), r.listOfMaps);
        assertEquals(List.of(id), r.uuids);
        assertEquals(Set.of(Color.RED, Color.GREEN), r.colors);
        assertTrue(r.bits.get(2) && r.bits.get(4));
    }

    @Test
    @DisplayName("Unsupported map key type fails loudly")
    void reflection_unsupported_map_key() {
        assertThrows(JsonException.class,
            () -> readReflective("{\"byColor\":{\"PURPLE\":1}}"),
            "unknown enum constant should not be silently accepted");
    }

    @Test
    @DisplayName("Field array")
    void array() {
        JsonAdapter adapter = new JsonAdapter(config());
        String[] strs = {"A","B","CK"};
        String written = adapter.writeValue(strs);
        assertEquals("[\"A\",\"B\",\"CK\"]",written);
    }

    @Test
    @DisplayName("Field array in map")
    void array_in_map() {
        JsonAdapter adapter = new JsonAdapter(config());
        Map<String, String[]> map = new HashMap<>();
        map.put("infoA", new String[]{"aa", "bb"});
        map.put("infoB", new String[]{"cc", "dd"});
        String written = adapter.writeValue(map);
        assertEquals("{\"infoA\":[\"aa\",\"bb\"],\"infoB\":[\"cc\",\"dd\"]}",written);
    }

    @SerializedObject
    public static class Product {
        public int id;
        public Map<String, String[]> info;

        public Product(int id, Map<String, String[]> info) {
            this.id = id;
            this.info = info;
        }

        @Override
        public String toString() {
            return "Product{" +
                    "id=" + id +
                    ", info=" + info +
                    '}';
        }
    }

    @Test
    @DisplayName("Field array in object")
    void array_in_object() {
        JsonAdapter adapter = new JsonAdapter(config());
        Map<String, String[]> map = new HashMap<>();
        map.put("infoA", new String[]{"aa", "bb"});
        map.put("infoB", new String[]{"cc", "dd"});
        String written = adapter.writeValue(new Product(1024, map));
        assertEquals("{\"id\":1024,\"info\":{\"infoA\":[\"aa\",\"bb\"],\"infoB\":[\"cc\",\"dd\"]}}",written);
    }

    @SerializedObject
    public static class KeyedMaps {
        public Map<Integer, String> byInt;
        public Map<Long, Boolean> byLong;
        public Map<Color, Integer> byColor;
        public TreeMap<UUID, String> byUuid;
        public HashMap<String, Integer> plain;

        public KeyedMaps() {}
    }

    @Test
    @DisplayName("Generated code supports non-String map keys and honors the declared map type")
    void processor_map_key_types() {
        UUID id = UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
        JsonAdapter adapter = adapter();

        KeyedMaps m = adapter.readValue("{"
                + "\"byInt\":{\"1\":\"x\",\"2\":\"y\"},"
                + "\"byLong\":{\"9000000000\":true},"
                + "\"byColor\":{\"RED\":1,\"GREEN\":2},"
                + "\"byUuid\":{\"" + id + "\":\"found\"},"
                + "\"plain\":{\"a\":1}"
                + "}", KeyedMaps.class);

        assertEquals(Map.of(1, "x", 2, "y"), m.byInt);
        assertEquals(Map.of(9_000_000_000L, true), m.byLong);
        assertEquals(Map.of(Color.RED, 1, Color.GREEN, 2), m.byColor);
        assertEquals("found", m.byUuid.get(id));
        assertInstanceOf(TreeMap.class, m.byUuid);
        assertInstanceOf(HashMap.class, m.plain);

        KeyedMaps copy = adapter.readValue(adapter.writeValue(m), KeyedMaps.class);
        assertEquals(m.byInt, copy.byInt);
        assertEquals(m.byLong, copy.byLong);
        assertEquals(m.byColor, copy.byColor);
        assertEquals(m.byUuid, copy.byUuid);
    }
}