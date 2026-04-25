package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POJO using List.of(), Set.of(), Map.of() immutable collections.
 * Used to verify JPMS-safe serialization of JDK immutable collections.
 */
public class PojoWithImmutableCollections {
    public String label;
    public List<String> immutableList;
    public Set<Integer> immutableSet;
    public Map<String, String> immutableMap;

    public PojoWithImmutableCollections() {}

    public PojoWithImmutableCollections(String label, List<String> immutableList,
                                         Set<Integer> immutableSet, Map<String, String> immutableMap) {
        this.label = label;
        this.immutableList = immutableList;
        this.immutableSet = immutableSet;
        this.immutableMap = immutableMap;
    }
}
