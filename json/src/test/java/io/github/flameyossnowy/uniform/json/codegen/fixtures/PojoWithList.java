package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import java.util.List;


public class PojoWithList {
    public String label;
    public List<String> tags;
    public List<Integer> scores;
    public List<SimplePojo> children;

    public PojoWithList() {}
    public PojoWithList(String label, List<String> tags, List<Integer> scores, List<SimplePojo> children) {
        this.label = label; this.tags = tags; this.scores = scores; this.children = children;
    }

    @Override
    public String toString() {
        return "PojoWithList{" +
            "label='" + label + '\'' +
            ", tags=" + tags +
            ", scores=" + scores +
            ", children=" + children +
            '}';
    }
}