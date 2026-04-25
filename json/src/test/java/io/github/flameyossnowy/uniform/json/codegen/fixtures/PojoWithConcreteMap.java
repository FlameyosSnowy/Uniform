package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.LinkedHashMap;

/**
 * POJO with concrete LinkedHashMap implementations.
 * Used to test that private internal fields (head, tail, accessOrder) are NOT serialized.
 */
public class PojoWithConcreteMap {
    public String label;
    public LinkedHashMap<String, Integer> orderedNumbers;
    public LinkedHashMap<String, String> orderedStrings;

    public PojoWithConcreteMap() {}

    public PojoWithConcreteMap(String label, LinkedHashMap<String, Integer> orderedNumbers,
                                LinkedHashMap<String, String> orderedStrings) {
        this.label = label;
        this.orderedNumbers = orderedNumbers;
        this.orderedStrings = orderedStrings;
    }
}
