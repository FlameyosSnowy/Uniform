package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
public class PojoWithList {
    public String label;
    public List<String> tags;
    public List<Integer> scores;
    public List<SimplePojo> children;

    public PojoWithList() {}
    public PojoWithList(String label, List<String> tags, List<Integer> scores, List<SimplePojo> children) {
        this.label = label; this.tags = tags; this.scores = scores; this.children = children;
    }
}