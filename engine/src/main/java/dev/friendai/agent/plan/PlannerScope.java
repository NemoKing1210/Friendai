package dev.friendai.agent.plan;

import dev.friendai.agent.tools.ToolGroup;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which tools one planning occasion may plan with. The planner prompt, its schema map and the parser's allowlist all
 * derive from this set, so a narrow occasion cannot pay for a tool it will never name and a repair cannot lose the tool
 * it has to repair.
 *
 * <p>The rule is data, not a branch in the factory: a group is dropped only when no plannable tool lives in it, so a
 * group that holds a real plan step — {@code wait}, a resource search, a known place, a flight, an interaction — is
 * kept with all of its tools. A goal and a free-time shift keep everything. A tool the catalog does not know is always
 * kept, matching {@code ToolCatalog}'s own rule, so a missing entry can never hide a tool by accident.
 */
public final class PlannerScope {
    private PlannerScope() {}

    /**
     * The tool names this subject may plan with. A name absent from {@code groups} is always kept.
     *
     * @param plannable the tool names that may appear as a plan step; a group is kept while one of them lives in it
     * @param planStepTools the tools the plan being repaired already names, kept whatever their group
     */
    public static Set<String> toolNames(
            PlanSubject subject, Map<String, ToolGroup> groups, Set<String> plannable, Set<String> planStepTools) {
        Set<String> steps = planStepTools == null ? Set.of() : planStepTools;
        Set<String> kept = new LinkedHashSet<>();
        if (!restricted(subject)) {
            kept.addAll(groups.keySet());
            kept.addAll(steps);
            return kept;
        }
        Set<ToolGroup> withAPlannableTool = EnumSet.noneOf(ToolGroup.class);
        for (String name : plannable == null ? Set.<String>of() : plannable) {
            ToolGroup group = groups.get(name);
            if (group != null) {
                withAPlannableTool.add(group);
            }
        }
        groups.forEach((name, group) -> {
            if (steps.contains(name) || withAPlannableTool.contains(group)) {
                kept.add(name);
            }
        });
        kept.addAll(steps);
        return kept;
    }

    /** An order and a repair plan world work; a goal and a shift may use every plannable tool. */
    private static boolean restricted(PlanSubject subject) {
        return subject instanceof PlanSubject.OwnerOrder || subject instanceof PlanSubject.TroubledPlan;
    }
}
