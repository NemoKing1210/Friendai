package dev.friendai.agent.tools;

import java.util.List;

/**
 * What the agent layer knows about one tool besides its schema.
 *
 * @param core always visible, whatever the turn's mode
 * @param whenToUse guidance shown in the prompt while the tool is visible; empty when the schema says enough
 * @param requires tools or items worth having first, as short plain phrases
 */
public record ToolMeta(String name, ToolGroup group, boolean core, String whenToUse, List<String> requires, Cost cost) {
    public ToolMeta {
        whenToUse = whenToUse == null ? "" : whenToUse;
        requires = List.copyOf(requires);
    }
}
