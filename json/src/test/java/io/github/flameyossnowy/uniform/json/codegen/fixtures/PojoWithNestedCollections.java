package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * POJO with nested collections.
 * Used to test that private internal fields are NOT serialized at any nesting level.
 */
public class PojoWithNestedCollections {
    public String label;
    public List<ArrayList<String>> nestedLists;
    public Map<String, HashMap<String, String>> nestedMaps;

    public PojoWithNestedCollections() {}

    public PojoWithNestedCollections(String label, List<ArrayList<String>> nestedLists,
                                      Map<String, HashMap<String, String>> nestedMaps) {
        this.label = label;
        this.nestedLists = nestedLists;
        this.nestedMaps = nestedMaps;
    }
}
