package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * POJO with concrete collection implementations (not just interfaces).
 * Used to test that private internal fields of collections are NOT serialized.
 */
public class PojoWithConcreteCollections {
    public String label;
    public ArrayList<String> items;
    public HashMap<String, Integer> mapping;
    public HashSet<Integer> uniqueIds;

    public PojoWithConcreteCollections() {}

    public PojoWithConcreteCollections(String label, ArrayList<String> items,
                                        HashMap<String, Integer> mapping, HashSet<Integer> uniqueIds) {
        this.label = label;
        this.items = items;
        this.mapping = mapping;
        this.uniqueIds = uniqueIds;
    }
}
