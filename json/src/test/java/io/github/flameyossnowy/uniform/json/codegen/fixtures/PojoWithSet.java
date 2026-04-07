package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;
import java.util.Set;


public class PojoWithSet {
    public String label;
    public Set<String> permissions;
    public Set<Integer> ids;

    public PojoWithSet() {}
    public PojoWithSet(String label, Set<String> permissions, Set<Integer> ids) {
        this.label = label; this.permissions = permissions; this.ids = ids;
    }
}