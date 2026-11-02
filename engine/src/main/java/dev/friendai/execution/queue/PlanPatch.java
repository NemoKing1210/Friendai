package dev.friendai.execution.queue;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure replacement of one node range in a plan definition. It is the bounded tree edit that a patch applies before
 * the cursor is remapped; it never touches the queue and never resolves a branch by itself.
 */
public final class PlanPatch {
    private PlanPatch() {}

    public static PlanDefinition replace(
            PlanDefinition plan, List<Integer> parentRoute, int index, int removeCount, List<PlanNode> replacement) {
        if (plan == null || parentRoute == null || parentRoute.size() % 2 != 0) {
            throw new IllegalArgumentException("invalid patch");
        }
        if (index < 0 || removeCount < 0 || replacement == null) throw new IllegalArgumentException("invalid range");
        if (parentRoute.isEmpty()) {
            return new PlanDefinition(replaceRange(plan.nodes(), index, removeCount, replacement));
        }
        return new PlanDefinition(replaceNested(plan.nodes(), parentRoute, 0, index, removeCount, replacement));
    }

    private static List<PlanNode> replaceNested(
            List<PlanNode> nodes,
            List<Integer> route,
            int offset,
            int index,
            int removeCount,
            List<PlanNode> replacement) {
        int parentIndex = route.get(offset);
        int kind = route.get(offset + 1);
        if (parentIndex < 0 || parentIndex >= nodes.size()) throw new IllegalArgumentException("route");
        PlanNode parent = nodes.get(parentIndex);
        List<PlanNode> result = new ArrayList<>(nodes);
        PlanNode changed;
        if (parent instanceof PlanNode.Conditional conditional && (kind == 1 || kind == 2)) {
            List<PlanNode> branch = kind == 1 ? conditional.thenNodes() : conditional.elseNodes();
            List<PlanNode> patched = offset + 2 == route.size()
                    ? replaceRange(branch, index, removeCount, replacement)
                    : replaceNested(branch, route, offset + 2, index, removeCount, replacement);
            changed = kind == 1
                    ? new PlanNode.Conditional(conditional.condition(), patched, conditional.elseNodes())
                    : new PlanNode.Conditional(conditional.condition(), conditional.thenNodes(), patched);
        } else if (parent instanceof PlanNode.Repeat repeat && kind == 3) {
            List<PlanNode> patched = offset + 2 == route.size()
                    ? replaceRange(repeat.body(), index, removeCount, replacement)
                    : replaceNested(repeat.body(), route, offset + 2, index, removeCount, replacement);
            changed = new PlanNode.Repeat(patched, repeat.until(), repeat.maxIterations());
        } else {
            throw new IllegalArgumentException("route kind does not match");
        }
        result.set(parentIndex, changed);
        return List.copyOf(result);
    }

    private static List<PlanNode> replaceRange(
            List<PlanNode> nodes, int index, int removeCount, List<PlanNode> replacement) {
        if (index < 0 || index > nodes.size() || index + removeCount > nodes.size()) {
            throw new IllegalArgumentException("range");
        }
        List<PlanNode> result = new ArrayList<>(nodes);
        result.subList(index, index + removeCount).clear();
        result.addAll(index, replacement);
        if (result.isEmpty()) throw new IllegalArgumentException("a plan cannot become empty");
        return List.copyOf(result);
    }
}
