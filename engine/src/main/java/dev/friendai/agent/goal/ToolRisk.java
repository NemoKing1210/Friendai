package dev.friendai.agent.goal;

import dev.friendai.agent.tools.ToolCatalog;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import java.util.Collection;
import java.util.EnumSet;
import java.util.Set;

/**
 * Maps the tools a goal's queued work uses to its {@link RiskClass}. Pure and stateless: nothing is saved with the
 * goal, so a replan that adds or drops a tool changes the class the prompt shows.
 *
 * <p>The rule, applied per tool name:
 *
 * <ol>
 *   <li>a named destructive tool, or any tool in the fighting ({@code COMBAT}) or creative ({@code CONTROL})
 *       group, is {@link RiskClass#DESTRUCTIVE};
 *   <li>a named read-only or preference-only tool, or any tool in the goal, memory, information, place or body
 *       group, is {@link RiskClass#SAFE};
 *   <li>everything else — mining, building, farming, crafting, storing, trading, movement — is {@link
 *       RiskClass#CAREFUL}, and so is a tool the catalog does not know, because an unknown effect is never assumed
 *       harmless.
 * </ol>
 *
 * A goal's class is the highest class among its tools, so one destructive tool wins over any number of safe ones.
 */
public final class ToolRisk {
    private static final Set<ToolGroup> SAFE_GROUPS =
            EnumSet.of(ToolGroup.CORE, ToolGroup.MEMORY, ToolGroup.INFO, ToolGroup.PLACE, ToolGroup.BODY);

    private static final Set<ToolGroup> DESTRUCTIVE_GROUPS = EnumSet.of(ToolGroup.COMBAT, ToolGroup.CONTROL);

    /** Tools that only read or change the companion's own preferences, even inside a louder group. */
    private static final Set<String> SAFE_NAMES = Set.of(
            "get_combat_settings",
            "set_combat_settings",
            "set_name",
            "show_inventory",
            "check_container",
            "find_traders",
            "inspect_trader",
            "refresh_trades",
            "wait_for_restock",
            "known_trades",
            "plan_trade",
            "plan_obtain",
            "known_resources",
            "plan_farm",
            "blueprint_help",
            "preview_structure",
            "list_blueprints",
            "plan_special",
            "get_recipe",
            "get_food_rules",
            "allow_food",
            "forbid_food",
            "reset_food_rule");

    /** Fighting and creative-only powers, and clearing a plot that may hold the owner's blocks. */
    private static final Set<String> DESTRUCTIVE_NAMES =
            Set.of("attack", "attack_player", "create_item", "fly_to", "execute_command", "clear_area");

    private static final ToolCatalog CATALOG = ToolCatalog.defaults();

    private ToolRisk() {}

    /** The class of the work behind a goal; an empty tool set is {@link RiskClass#SAFE}. */
    public static RiskClass of(Collection<String> tools) {
        RiskClass risk = RiskClass.SAFE;
        if (tools == null) {
            return risk;
        }
        for (String tool : tools) {
            risk = risk.max(ofTool(tool));
        }
        return risk;
    }

    /** The class of one tool; unknown or blank names are {@link RiskClass#CAREFUL}. */
    public static RiskClass ofTool(String tool) {
        String name = tool == null ? "" : tool.strip();
        if (DESTRUCTIVE_NAMES.contains(name)) {
            return RiskClass.DESTRUCTIVE;
        }
        if (SAFE_NAMES.contains(name)) {
            return RiskClass.SAFE;
        }
        return CATALOG.meta(name).map(ToolRisk::ofGroup).orElse(RiskClass.CAREFUL);
    }

    private static RiskClass ofGroup(ToolMeta meta) {
        if (DESTRUCTIVE_GROUPS.contains(meta.group())) {
            return RiskClass.DESTRUCTIVE;
        }
        return SAFE_GROUPS.contains(meta.group()) ? RiskClass.SAFE : RiskClass.CAREFUL;
    }
}
