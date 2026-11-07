package dev.friendai.agent.plan;

import dev.friendai.agent.tools.ToolGroup;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which tools one planning occasion may plan with. The planner prompt, its schema map and the parser's allowlist all
 * derive from this set, so a narrow occasion cannot pay for a tool it will never name and a repair cannot lose the tool
 * it has to repair.
 *
 * <p>The rule is data, not a branch in the factory: an owner order plans world work, so the groups that answer a
 * question, move the body, edit a place or a setting, or talk to another player are dropped; a goal and a free-time
 * shift keep everything. A tool the catalog does not know is always kept, matching {@code ToolCatalog}'s own rule, so a
 * missing entry can never hide a tool by accident.
 */
public final class PlannerScope {
    /** Groups an owner order or a repair never plans with: none of them is a step of a queued job. */
    private static final Set<ToolGroup> NOT_FOR_A_PLAN = Set.of(
            ToolGroup.INFO,
            ToolGroup.BODY,
            ToolGroup.PLACE,
            ToolGroup.TRUST,
            ToolGroup.CONTROL,
            ToolGroup.AUTONOMY,
            ToolGroup.INTERACTION);

    private PlannerScope() {}

    /** The tool names this subject may plan with. A name absent from {@code groups} is always kept. */
    public static Set<String> toolNames(PlanSubject subject, Map<String, ToolGroup> groups, Set<String> planStepTools) {
        Set<String> steps = planStepTools == null ? Set.of() : planStepTools;
        Set<String> kept = new LinkedHashSet<>();
        if (!restricted(subject)) {
            kept.addAll(groups.keySet());
        } else {
            groups.forEach((name, group) -> {
                if (steps.contains(name) || !NOT_FOR_A_PLAN.contains(group)) {
                    kept.add(name);
                }
            });
        }
        kept.addAll(steps);
        return kept;
    }

    /** An order and a repair plan world work; a goal and a shift may use every plannable tool. */
    private static boolean restricted(PlanSubject subject) {
        return subject instanceof PlanSubject.OwnerOrder || subject instanceof PlanSubject.TroubledPlan;
    }
}
