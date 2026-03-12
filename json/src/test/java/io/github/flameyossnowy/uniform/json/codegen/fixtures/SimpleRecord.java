package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;

@SerializedObject
public record SimpleRecord(int id, String name) {
}
