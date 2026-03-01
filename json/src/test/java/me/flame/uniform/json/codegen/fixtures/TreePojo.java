// me/flame/uniform/json/codegen/fixtures/TreePojo.java
package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
public class TreePojo {
    public int id;
    public String name;
    public List<TreePojo> children;

    public TreePojo() {}
    public TreePojo(int id, String name, List<TreePojo> children) {
        this.id = id;
        this.name = name;
        this.children = children;
    }
}