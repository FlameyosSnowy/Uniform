package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


public class NestedPojo {
    public SimplePojo child;

    public NestedPojo() {
    }

    public NestedPojo(SimplePojo child) {
        this.child = child;
    }
}
