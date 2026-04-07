package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


public class Circle implements Shape {
    public int radius;

    public Circle() {
    }

    public Circle(int radius) {
        this.radius = radius;
    }
}
