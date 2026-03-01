// me/flame/uniform/json/codegen/fixtures/ContainerPojo.java
package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;
import java.util.List;

@SerializedObject
public class ContainerPojo {
    public int id;
    public List<SimplePojo> items;

    public ContainerPojo() {}
    public ContainerPojo(int id, List<SimplePojo> items) {
        this.id = id;
        this.items = items;
    }
}