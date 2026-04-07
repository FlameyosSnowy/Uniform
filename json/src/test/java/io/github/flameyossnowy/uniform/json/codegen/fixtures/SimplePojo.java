package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


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
