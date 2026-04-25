package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.ArrayList;
import java.util.List;

/**
 * POJO with both List interface and ArrayList concrete type fields.
 * Used to test that both are serialized identically without exposing private fields.
 */
public class PojoWithListAndArrayList {
    public String label;
    public List<String> listInterface;
    public ArrayList<String> arrayListConcrete;

    public PojoWithListAndArrayList() {}

    public PojoWithListAndArrayList(String label, List<String> listInterface, ArrayList<String> arrayListConcrete) {
        this.label = label;
        this.listInterface = listInterface;
        this.arrayListConcrete = arrayListConcrete;
    }
}
