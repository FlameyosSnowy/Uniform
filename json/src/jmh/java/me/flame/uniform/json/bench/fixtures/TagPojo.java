package me.flame.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
@CompiledJson
public class TagPojo {
    public int id;
    public String label;
    public String color;

    public TagPojo() {}

    public TagPojo(int id, String label, String color) {
        this.id = id; this.label = label; this.color = color;
    }
}