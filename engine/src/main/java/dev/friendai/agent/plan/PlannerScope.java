package dev.friendai.agent.plan;

import dev.friendai.agent.tools.ToolGroup;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Which tools one planning occasion may plan with. Every occasion keeps the whole plannable set: an owner order
 * legitimately names a step from any group, so narrowing it by group would remove real capability. A repair is the one
 * exception — it keeps the groups its steps belong to, so the replacement step can use a sibling tool of the same
 * group.
 *
 * <p>Every tool in the map carries a {@link ToolGroup}, and a repair can only use tools present in the map because the
 * caller intersects the selection with the plannable set before the planner sees it.
 */
public final class PlannerScope {
    private PlannerScope() {}

    /**
     * The tool names this subject may plan with: every plannable tool, except that a repair keeps only the groups its
     * steps belong to.
     *
     * @param planStepTools the tools the plan's jobs name; never null
     */
    public static Set<String> toolNames(PlanSubject subject, Map<String, ToolGroup> tools, Set<String> planStepTools) {
        if (!(subject instanceof PlanSubject.TroubledPlan)) {
            return new LinkedHashSet<>(tools.keySet());
        }
        Set<ToolGroup> repairGroups = EnumSet.noneOf(ToolGroup.class);
        for (String step : planStepTools) {
            ToolGroup group = tools.get(step);
            if (group != null) {
                repairGroups.add(group);
            }
        }
        Set<String> kept = new LinkedHashSet<>();
        tools.forEach((name, group) -> {
            if (repairGroups.contains(group)) {
                kept.add(name);
            }
        });
        return kept;
    }
}
