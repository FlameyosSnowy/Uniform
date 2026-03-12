package io.github.flameyossnowy.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;

@SerializedObject
@CompiledJson
public class ComplexBenchPojo {
    public int id;
    public String name;
    public SimpleBenchPojo child;
    public int count;

    public ComplexBenchPojo() {
    }

    public ComplexBenchPojo(int id, String name, SimpleBenchPojo child, int count) {
        this.id = id;
        this.name = name;
        this.child = child;
        this.count = count;
    }
}
