package dev.friendai.execution.queue;

import java.util.List;

/** A validated immutable plan tree. */
public record PlanDefinition(List<PlanNode> nodes) {
    public PlanDefinition {
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        if (nodes.isEmpty()) throw new IllegalArgumentException("plan must not be empty");
    }

    /** Returns the bounded worst-case number of emitted actions, saturating on overflow. */
    public long worstCaseActions() {
        long total = 0;
        for (PlanNode node : nodes) total = saturatedAdd(total, cost(node));
        return total;
    }

    private static long cost(PlanNode node) {
        if (node instanceof PlanNode.Action) return 1;
        if (node instanceof PlanNode.Conditional conditional) {
            return Math.max(cost(conditional.thenNodes()), cost(conditional.elseNodes()));
        }
        PlanNode.Repeat repeat = (PlanNode.Repeat) node;
        return saturatedMultiply(cost(repeat.body()), repeat.maxIterations());
    }

    private static long cost(List<PlanNode> nodes) {
        long total = 0;
        for (PlanNode node : nodes) total = saturatedAdd(total, cost(node));
        return total;
    }

    private static long saturatedAdd(long a, long b) {
        return Long.MAX_VALUE - a < b ? Long.MAX_VALUE : a + b;
    }

    private static long saturatedMultiply(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }
}
