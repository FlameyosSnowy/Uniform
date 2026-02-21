package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;

@SerializedObject
public class Circle implements Shape {
    public int radius;

    public Circle() {
    }

    public Circle(int radius) {
        this.radius = radius;
    }
}
