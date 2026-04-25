package io.github.flameyossnowy.uniform.json.codegen.fixtures;

import java.util.PriorityQueue;

/**
 * POJO with concrete PriorityQueue implementation.
 * Used to test that private internal fields (queue, comparator, modCount) are NOT serialized.
 */
public class PojoWithConcreteQueue {
    public String label;
    public PriorityQueue<Integer> priorities;

    public PojoWithConcreteQueue() {}

    public PojoWithConcreteQueue(String label, PriorityQueue<Integer> priorities) {
        this.label = label;
        this.priorities = priorities;
    }
}
