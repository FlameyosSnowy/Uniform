package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
public class HasShape {
    public Shape shape;

    public HasShape() {
    }

    public HasShape(Shape shape) {
        this.shape = shape;
    }
}
