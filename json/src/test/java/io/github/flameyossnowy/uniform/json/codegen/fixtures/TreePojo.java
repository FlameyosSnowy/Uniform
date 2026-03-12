// me/flame/uniform/json/codegen/fixtures/TreePojo.java
package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
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