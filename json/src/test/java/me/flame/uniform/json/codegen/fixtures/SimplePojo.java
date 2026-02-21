package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
public class SimplePojo {
    public int id;
    public String name;

    public SimplePojo() {
    }

    public SimplePojo(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
