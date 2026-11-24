package dev.friendai.execution.queue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** One immutable node in a bounded conditional plan. */
public sealed interface PlanNode permits PlanNode.Action, PlanNode.Conditional, PlanNode.Repeat {
    /** Plan ids this node's conditions ask about, so the queue keeps their archives while the plan is live. */
    default Set<Long> referencedPlanIds() {
        return Set.of();
    }

    record Action(QueuedStep step) implements PlanNode {
        public Action {
            if (step == null) throw new IllegalArgumentException("step");
        }
    }

    record Conditional(Guard condition, List<PlanNode> thenNodes, List<PlanNode> elseNodes) implements PlanNode {
        public Conditional {
            if (condition == null) throw new IllegalArgumentException("condition");
            thenNodes = List.copyOf(thenNodes == null ? List.of() : thenNodes);
            elseNodes = List.copyOf(elseNodes == null ? List.of() : elseNodes);
        }

        @Override
        public Set<Long> referencedPlanIds() {
            Set<Long> ids = new HashSet<>(condition.referencedPlanIds());
            thenNodes.forEach(node -> ids.addAll(node.referencedPlanIds()));
            elseNodes.forEach(node -> ids.addAll(node.referencedPlanIds()));
            return Set.copyOf(ids);
        }
    }

    record Repeat(List<PlanNode> body, Guard until, int maxIterations) implements PlanNode {
        public Repeat {
            body = List.copyOf(body == null ? List.of() : body);
            if (body.isEmpty() || until == null || maxIterations < 1 || maxIterations > 64) {
                throw new IllegalArgumentException("invalid repeat");
            }
        }

        @Override
        public Set<Long> referencedPlanIds() {
            Set<Long> ids = new HashSet<>(until.referencedPlanIds());
            body.forEach(node -> ids.addAll(node.referencedPlanIds()));
            return Set.copyOf(ids);
        }
    }
}
