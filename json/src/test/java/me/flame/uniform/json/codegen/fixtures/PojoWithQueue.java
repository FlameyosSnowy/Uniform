package me.flame.uniform.json.codegen.fixtures;

import me.flame.uniform.core.annotations.SerializedObject;
import java.util.Queue;

@SerializedObject
public class PojoWithQueue {
    public String label;
    public Queue<String> tasks;
    public Queue<Integer> priorities;

    public PojoWithQueue() {}
    public PojoWithQueue(String label, Queue<String> tasks, Queue<Integer> priorities) {
        this.label = label; this.tasks = tasks; this.priorities = priorities;
    }
}