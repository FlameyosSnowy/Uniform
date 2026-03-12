package io.github.flameyossnowy.uniform.json.bench.fixtures;

import com.dslplatform.json.CompiledJson;
import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;

@SerializedObject
@CompiledJson
public class SimpleBenchPojo {
    public int id;
    public String name;

    public SimpleBenchPojo() {
    }

    public SimpleBenchPojo(int id, String name) {
        this.id = id;
        this.name = name;
    }
}
