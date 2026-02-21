package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
public class NestedPojo {
    public SimplePojo child;

    public NestedPojo() {
    }

    public NestedPojo(SimplePojo child) {
        this.child = child;
    }
}
