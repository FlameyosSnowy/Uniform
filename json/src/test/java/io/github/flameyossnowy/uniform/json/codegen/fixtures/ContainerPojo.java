// me/flame/uniform/json/codegen/fixtures/ContainerPojo.java
package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
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