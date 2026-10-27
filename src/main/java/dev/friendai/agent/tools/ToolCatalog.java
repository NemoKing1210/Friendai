package dev.friendai.agent.tools;

import dev.friendai.llm.model.ToolSpec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Which group each tool belongs to and what to tell the model about when to use it. Pure data: a tool the
 * catalog does not know is treated as always visible, so a missing entry can never hide a tool by accident.
 */
public final class ToolCatalog {
    private final Map<String, ToolMeta> byName = new LinkedHashMap<>();

    public ToolCatalog(List<ToolMeta> metas) {
        for (ToolMeta meta : metas) {
            byName.put(meta.name(), meta);
        }
    }

    public Optional<ToolMeta> meta(String tool) {
        return Optional.ofNullable(byName.get(tool));
    }

    public List<ToolMeta> all() {
        return List.copyOf(byName.values());
    }

    public boolean visible(String tool, Set<ToolGroup> groups) {
        ToolMeta meta = byName.get(tool);
        return meta == null || meta.core() || groups.contains(meta.group());
    }

    /** Hides the only spending tool on a chat turn while leaving remembered market facts queryable. */
    public boolean visible(String tool, Set<ToolGroup> groups, boolean readOnlyTrading) {
        return !(readOnlyTrading && tool.equals("trade_item")) && visible(tool, groups);
    }

    /** Names of the cataloged tools shown when exactly these groups are unlocked, core tools included. */
    public Set<String> namesFor(Set<ToolGroup> groups) {
        Set<String> names = new LinkedHashSet<>();
        for (ToolMeta meta : byName.values()) {
            if (meta.core() || groups.contains(meta.group())) {
                names.add(meta.name());
            }
        }
        return names;
    }

    /** The specs a turn with these groups may send, in their original order. */
    public List<ToolSpec> filter(List<ToolSpec> specs, Set<ToolGroup> groups) {
        return filter(specs, groups, false);
    }

    public List<ToolSpec> filter(List<ToolSpec> specs, Set<ToolGroup> groups, boolean readOnlyTrading) {
        List<ToolSpec> kept = new ArrayList<>();
        for (ToolSpec spec : specs) {
            if (visible(spec.name(), groups, readOnlyTrading)) {
                kept.add(spec);
            }
        }
        return kept;
    }

    /** One {@code name: whenToUse} line per visible tool that has guidance. */
    public List<String> hints(Set<ToolGroup> groups) {
        return hints(groups, false);
    }

    public List<String> hints(Set<ToolGroup> groups, boolean readOnlyTrading) {
        List<String> lines = new ArrayList<>();
        for (ToolMeta meta : byName.values()) {
            if (!meta.whenToUse().isBlank() && visible(meta.name(), groups, readOnlyTrading)) {
                lines.add(meta.whenToUse());
            }
        }
        return lines;
    }

    public static ToolGroup[] allGroups() {
        return ToolGroup.values();
    }

    public static ToolCatalog defaults() {
        List<ToolMeta> metas = new ArrayList<>();
        core(metas, ToolGroup.CORE, "stop", "queue_add", "queue_show", "queue_edit", "look_around", "get_inventory",
                "set_goal", "update_goal", "finish_goal", "request_tools", "find_place");
        core(metas, ToolGroup.MEMORY, "remember", "recall", "forget", "use_skill", "manage_skill");
        group(metas, ToolGroup.MOVEMENT, Cost.MEDIUM, "follow_player", "stay", "go_to", "jump", "set_pace",
                "set_follow_mode", "step", "back_away", "approach", "circle");
        group(metas, ToolGroup.GATHERING, Cost.HIGH, "fell_tree", "mine_blocks", "mine_block_at", "pickup_items");
        group(metas, ToolGroup.INVENTORY, Cost.LOW, "give_items", "drop_items", "show_inventory");
        group(metas, ToolGroup.BUILDING, Cost.MEDIUM, "place_block", "use_block", "light_area");
        group(metas, ToolGroup.STORAGE, Cost.MEDIUM, "store_items", "take_items", "check_container",
                "deposit_items");
        group(metas, ToolGroup.CRAFTING, Cost.MEDIUM, "craft_item", "get_recipe");
        group(metas, ToolGroup.COOKING, Cost.MEDIUM, "smelt_items", "cook_food", "collect_smelted");
        group(metas, ToolGroup.ENCHANT, Cost.MEDIUM, "enchant_item", "anvil_item", "grindstone_item");
        group(metas, ToolGroup.COMBAT, Cost.MEDIUM, "attack", "attack_player", "set_combat_mode", "get_combat_settings",
                "set_combat_settings", "equip_item", "eat",
                "get_food", "allow_food", "forbid_food", "reset_food_rule", "get_food_rules");
        group(metas, ToolGroup.FARMING, Cost.HIGH, "farm", "plan_farm", "tend_farm", "use_bonemeal");
        group(metas, ToolGroup.ANIMALS, Cost.MEDIUM, "breed_animals", "shear", "milk", "tame_animal", "lead_animals",
                "harvest_honey", "fish");
        group(metas, ToolGroup.OBTAIN, Cost.HIGH, "obtain_item", "plan_obtain");
        group(metas, ToolGroup.TRADING, Cost.HIGH, "find_traders", "inspect_trader", "refresh_trades",
                "wait_for_restock", "known_trades", "plan_trade", "trade_item", "level_trader", "barter");
        group(metas, ToolGroup.RESOURCE, Cost.HIGH, "search_resource", "known_resources");
        group(metas, ToolGroup.BLUEPRINT, Cost.HIGH, "blueprint_help", "preview_structure", "build_structure",
                "save_blueprint", "list_blueprints");
        group(metas, ToolGroup.STRUCTURE, Cost.HIGH, "build_special", "plan_special");
        group(metas, ToolGroup.INFO, Cost.LOW, "find_trees", "find_blocks", "player_view", "describe_block",
                "survey_area");
        group(metas, ToolGroup.PLACE, Cost.LOW, "remember_place", "go_to_place", "rename_place", "forget_place");
        group(metas, ToolGroup.TRUST, Cost.LOW, "trust_player", "untrust_player");
        group(metas, ToolGroup.CONTROL, Cost.LOW, "set_name", "create_item", "fly_to", "execute_command");
        group(metas, ToolGroup.BODY, Cost.LOW, "look_at", "point_at", "turn", "sneak", "emote", "sit", "stand", "wait",
                "swap_hands", "set_idle_behavior", "set_follow_distance");
        group(metas, ToolGroup.INTERACTION, Cost.MEDIUM, "interact_entity", "mount", "dismount", "sleep",
                "throw_item");
        hint(metas, "fell_tree",
                "- To chop a tree, call find_trees when the kind or the exact tree is unclear, then fell_tree. It fells that whole tree from the lowest log up. Pass limit only when the player asks for a few logs or to hurry; pass collect:false only when speed matters more than the drops.\n"
                        + "- When the player says 'near me', near: \"me\" is the default for the tree tools and means their position; near: \"self\" means yours.");
        hint(metas, "mine_blocks",
                "- Use mine_blocks for a kind of block (ore, stone, a named log type); use fell_tree when the player asks for a tree.");
        hint(metas, "obtain_item",
                "- To get an item you don't have, prefer obtain_item over long hand-written plans; use plan_obtain to explain what it will take.\n"
                        + "- In creative mode, obtain_item can supply a registered item directly for an authorized owner.");
        hint(metas, "create_item",
                "- In creative mode, use create_item for resources instead of mining unnecessarily. It reports how many fit and keeps work bounded; choose owner only when the owner is nearby in the same dimension.");
        hint(metas, "fly_to",
                "- In creative mode, use fly_to for an inaccessible target or a gap the ground route cannot cross. It reports failure when no collision-safe route through loaded chunks exists.");
        hint(metas, "execute_command",
                "- In creative mode, execute_command accepts only allowlisted teleport/time/weather/item/effect/enchant forms and uses the owner's live command permissions. Use @s for yourself, give/enchant only for the owner, and describe the returned feedback without claiming a denied or failed command succeeded.");
        hint(metas, "build_structure",
                "- For buildings, prefer a generator for standard shapes, call blueprint_help for the format, and preview_structure before build_structure.");
        hint(metas, "set_follow_mode",
                "- \"Don't follow me\" means set_follow_mode with mode=off: it stops your idle walk and your work keeps running. Use stay only to wait in one place for a few minutes, and end_free_time only when the owner stops your free time.");
        hint(metas, "set_pace",
                "- To change how fast you travel, call set_pace: run when asked to hurry or run, walk when asked to slow down, auto to go back to the default.");
        hint(metas, "get_food",
                "- When you are hungry with no food: if the owner is near, ask them; otherwise call get_food.");
        hint(metas, "remember_place",
                "- When the owner teaches a place (\"remember this as home\"), call remember_place with the words they used; here:true stores their position, so do not guess coordinates.");
        group(metas, ToolGroup.AUTONOMY, Cost.LOW, "start_free_time", "end_free_time", "choose_activity");
        group(metas, ToolGroup.FIRE, Cost.MEDIUM, "extinguish_fire");
        hint(metas, "extinguish_fire",
                "- A fire is an emergency: call extinguish_fire at once for your own fire (target self), the owner's (owner), a burning mob (entity) or the fires around you (near). A water bucket is poured and taken back; without water a fire block is broken by hand. Never break a lit campfire — it is a cooking station — and never pour water in the nether.");
        hint(metas, "start_free_time",
                "- When the owner releases you (\"do your own thing\", \"do whatever you want\"), call start_free_time with their hint as wish; call end_free_time when they call you back.");
        hint(metas, "choose_activity",
                "- In a free-time event, pick the single most useful activity from the agenda and call choose_activity once; the planner writes the steps.");
        hint(metas, "deposit_items",
                "- To unload loot at the base, use deposit_items with the storage place name; it keeps your tools and food. Use store_items only for one container or one item.");
        hint(metas, "find_place",
                "- When a place name is uncertain, call find_place first, then go_to_place with the exact name from the result; never guess a name.");
        hint(metas, "find_traders",
                "- Trading knowledge belongs to the owner. Find only loaded nearby traders; remembered locations and offers can be stale.");
        hint(metas, "refresh_trades",
                "- Refresh at most 20 explicitly selected traders; only complete live inspection replaces remembered offers.");
        hint(metas, "wait_for_restock",
                "- Wait between checks with merchant sessions closed; only real vanilla restocks count, and no stock or workstation is forced.");
        hint(metas, "known_trades",
                "- Known prices are historical quotes. Check freshness and inspect a trader before any purchase; never promise a piglin barter reward.");
        hint(metas, "plan_trade",
                "- plan_trade is read-only: check exact stock, output batches and every required currency before placing an order.");
        hint(metas, "trade_item",
                "- Spend only for an explicit owner order with a cap and reserve for every input. Respect missing currency and explicit offer refs; never buy filler to level a villager.");
        hint(metas, "level_trader",
                "- Level only the exact selected villager, using only the owner's allowed offer refs under one shared cumulative cap. Stop at the requested level; never buy filler.");
        hint(metas, "barter",
                "- Piglin barter is random. Pay only one plain gold ingot at a time under the owner's explicit cap, attribute only drops owned by the selected piglin, and never retry accepted gold with no output.");
        return new ToolCatalog(metas);
    }

    private static void hint(List<ToolMeta> metas, String name, String text) {
        for (int i = 0; i < metas.size(); i++) {
            ToolMeta meta = metas.get(i);
            if (meta.name().equals(name)) {
                metas.set(i, new ToolMeta(name, meta.group(), meta.core(), text, meta.requires(), meta.cost()));
                return;
            }
        }
        throw new IllegalStateException("no catalog entry for the hint on " + name);
    }

    private static void core(List<ToolMeta> metas, ToolGroup group, String... names) {
        for (String name : names) {
            metas.add(new ToolMeta(name, group, true, "", List.of(), Cost.LOW));
        }
    }

    private static void group(List<ToolMeta> metas, ToolGroup group, Cost cost, String... names) {
        for (String name : names) {
            metas.add(new ToolMeta(name, group, false, "", List.of(), cost));
        }
    }
}
