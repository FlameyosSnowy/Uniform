package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import java.util.Map;


public class PojoWithMap {
    public String label;
    public Map<String, Integer> counters;
    public Map<String, String>  metadata;
    public Map<String, SimplePojo> index;

    public PojoWithMap() {}
    public PojoWithMap(String label, Map<String, Integer> counters,
                       Map<String, String> metadata, Map<String, SimplePojo> index) {
        this.label = label; this.counters = counters;
        this.metadata = metadata; this.index = index;
    }
}