package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.TreeSet;

/**
 * POJO with concrete TreeSet implementation.
 * Used to test that private internal fields (m, comparator) are NOT serialized.
 */
public class PojoWithConcreteSet {
    public String label;
    public TreeSet<Integer> sortedIds;

    public PojoWithConcreteSet() {}

    public PojoWithConcreteSet(String label, TreeSet<Integer> sortedIds) {
        this.label = label;
        this.sortedIds = sortedIds;
    }
}
