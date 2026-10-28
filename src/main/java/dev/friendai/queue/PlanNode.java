package dev.friendai.queue;

import java.util.List;

/** One immutable node in a bounded conditional plan. */
public sealed interface PlanNode permits PlanNode.Action, PlanNode.Conditional, PlanNode.Repeat {
    record Action(QueuedStep step) implements PlanNode {
        public Action { if (step == null) throw new IllegalArgumentException("step"); }
    }

    record Conditional(Guard condition, List<PlanNode> thenNodes, List<PlanNode> elseNodes) implements PlanNode {
        public Conditional {
            if (condition == null) throw new IllegalArgumentException("condition");
            thenNodes = List.copyOf(thenNodes == null ? List.of() : thenNodes);
            elseNodes = List.copyOf(elseNodes == null ? List.of() : elseNodes);
        }
    }

    record Repeat(List<PlanNode> body, Guard until, int maxIterations) implements PlanNode {
        public Repeat {
            body = List.copyOf(body == null ? List.of() : body);
            if (body.isEmpty() || until == null || maxIterations < 1 || maxIterations > 64) {
                throw new IllegalArgumentException("invalid repeat");
            }
        }
    }
}
