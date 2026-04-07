package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import io.github.flameyossnowy.uniform.core.annotations.SerializedObject;


public class PojoWithArray {
    public String label;
    public int[] counts;
    public double[] ratios;
    public String[] names;

    public PojoWithArray() {}
    public PojoWithArray(String label, int[] counts, double[] ratios, String[] names) {
        this.label = label; this.counts = counts; this.ratios = ratios; this.names = names;
    }
}