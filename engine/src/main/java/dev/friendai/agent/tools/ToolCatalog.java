package dev.friendai.agent.tools;

import dev.friendai.agent.llm.model.ToolSpec;
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

    /** Returns a catalog with contribution metadata replacing defaults for matching names. */
    public ToolCatalog withOverrides(List<ToolMeta> overrides) {
        Map<String, ToolMeta> merged = new LinkedHashMap<>(byName);
        for (ToolMeta meta : overrides) {
            merged.put(meta.name(), meta);
        }
        return new ToolCatalog(new ArrayList<>(merged.values()));
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
        core(
                metas,
                ToolGroup.CORE,
                "stop",
                "queue_add",
                "plan_help",
                "queue_show",
                "queue_edit",
                "plan_patch",
                "look_around",
                "get_inventory",
                "set_goal",
                "update_goal",
                "finish_goal",
                "request_tools",
                "find_place");
        // A plan about to be queued is validated by queue_add itself, so the dry run is not advertised on an
        // ordinary action turn; request_tools unlocks it for an explicit validation request.
        group(metas, ToolGroup.PLANNING, Cost.LOW, "plan_validate");
        hint(
                metas,
                "plan_validate",
                "- Use plan_validate for validation-only requests or inspection of complex conditional/repeated plans; queue_add validates plans that are about to be queued.");
        core(
                metas,
                ToolGroup.MEMORY,
                "remember",
                "recall",
                "forget",
                "use_skill",
                "list_skills",
                "skill_help",
                "manage_skill",
                "read_log",
                "logs_search",
                "recent_chat",
                "memory_overview",
                "compress_memory",
                "reset_manner");
        group(
                metas,
                ToolGroup.MOVEMENT,
                Cost.MEDIUM,
                "follow_player",
                "stay",
                "go_to",
                "travel_through_portal",
                "jump",
                "set_pace",
                "set_follow_mode",
                "step",
                "back_away",
                "approach",
                "circle");
        hint(
                metas,
                "follow_player",
                "- Use follow_player when the owner asks you to come along and keep up; use go_to for one named destination.");
        hint(
                metas,
                "stay",
                "- Use stay to wait where you are; it ends early when queued work is ready, and it is not a way to refuse following.");
        hint(
                metas,
                "go_to",
                "- Use go_to for one destination the owner named; it may dig and bridge, and reports a failure when no route exists.");
        hint(
                metas,
                "travel_through_portal",
                "- Use travel_through_portal to go to the nether or back to the overworld. It walks into a lit portal within"
                        + " 16 blocks, builds and lights one from 10 obsidian and flint and steel when none is near, and"
                        + " records a return portal before leaving.");
        hint(metas, "jump", "- Use jump for a single hop; put several jump steps in one plan for repeats.");
        group(
                metas,
                ToolGroup.GATHERING,
                Cost.HIGH,
                "fell_tree",
                "mine_blocks",
                "mine_block_at",
                "excavate",
                "pickup_items");
        group(metas, ToolGroup.INVENTORY, Cost.LOW, "give_items", "drop_items", "show_inventory", "equip_item");
        hint(
                metas,
                "give_items",
                "- Use it to finish a job that was for the owner: their request is the permission to hand the items"
                        + " over, so give them yourself instead of asking.");
        group(metas, ToolGroup.BUILDING, Cost.MEDIUM, "place_block", "use_block", "light_area", "clear_area");
        group(
                metas,
                ToolGroup.STORAGE,
                Cost.MEDIUM,
                "store_items",
                "take_items",
                "check_container",
                "check_containers",
                "deposit_items");
        hint(
                metas,
                "check_containers",
                "- When an item may be in the chests here but you have not looked in them for a while, use"
                        + " check_containers before deciding to craft, mine or hunt it.");
        metas.add(new ToolMeta("move_items", ToolGroup.STORAGE, false, "", List.of(), Cost.MEDIUM));
        metas.add(new ToolMeta("find_stored_item", ToolGroup.STORAGE, false, "", List.of(), Cost.LOW));
        group(metas, ToolGroup.CRAFTING, Cost.MEDIUM, "craft_item", "get_recipe");
        group(metas, ToolGroup.COOKING, Cost.MEDIUM, "smelt_items", "cook_food", "collect_smelted");
        group(metas, ToolGroup.ENCHANT, Cost.MEDIUM, "enchant_item", "anvil_item", "grindstone_item");
        group(
                metas,
                ToolGroup.COMBAT,
                Cost.MEDIUM,
                "attack",
                "attack_player",
                "get_combat_settings",
                "set_combat_settings");
        group(
                metas,
                ToolGroup.SURVIVAL,
                Cost.MEDIUM,
                "eat",
                "get_food",
                "allow_food",
                "forbid_food",
                "reset_food_rule",
                "get_food_rules",
                "sleep");
        group(metas, ToolGroup.FARMING, Cost.HIGH, "farm", "plan_farm", "tend_farm", "use_bonemeal");
        group(
                metas,
                ToolGroup.ANIMALS,
                Cost.MEDIUM,
                "breed_animals",
                "shear",
                "milk",
                "tame_animal",
                "lead_animals",
                "harvest_honey",
                "fish");
        group(metas, ToolGroup.OBTAIN, Cost.HIGH, "obtain_item", "obtain_items", "plan_obtain");
        group(
                metas,
                ToolGroup.TRADING,
                Cost.HIGH,
                "find_traders",
                "inspect_trader",
                "refresh_trades",
                "wait_for_restock",
                "known_trades",
                "plan_trade",
                "trade_item",
                "level_trader",
                "barter");
        group(metas, ToolGroup.RESOURCE, Cost.HIGH, "search_resource", "known_resources");
        group(
                metas,
                ToolGroup.BLUEPRINT,
                Cost.HIGH,
                "blueprint_help",
                "preview_structure",
                "build_structure",
                "save_blueprint",
                "list_blueprints");
        group(metas, ToolGroup.STRUCTURE, Cost.HIGH, "build_special", "plan_special");
        group(
                metas,
                ToolGroup.INFO,
                Cost.LOW,
                "find_trees",
                "find_blocks",
                "find_placed",
                "player_view",
                "describe_block",
                "survey_area",
                "find_target",
                "find_structure",
                "search_structure",
                "search_hint");
        group(metas, ToolGroup.PLACE, Cost.LOW, "remember_place", "go_to_place", "rename_place", "forget_place");
        group(
                metas,
                ToolGroup.TRUST,
                Cost.LOW,
                "trust_player",
                "untrust_player",
                "request_help",
                "accept_help",
                "decline_help",
                "offer_handoff",
                "accept_handoff",
                "replan_plan");
        group(metas, ToolGroup.CONTROL, Cost.LOW, "set_name", "create_item", "fly_to", "execute_command");
        group(
                metas,
                ToolGroup.BODY,
                Cost.LOW,
                "look_at",
                "point_at",
                "turn",
                "sneak",
                "emote",
                "sit",
                "stand",
                "wait",
                "swap_hands",
                "set_idle_behavior",
                "set_follow_distance");
        group(metas, ToolGroup.INTERACTION, Cost.MEDIUM, "interact_entity", "mount", "dismount", "throw_item");
        hint(
                metas,
                "fell_tree",
                "- To chop a tree, call find_trees when the kind or the exact tree is unclear, then fell_tree. It climbs the trunk once and cuts the tree from the crown down, so the logs fall to the foot. limit takes the top N logs and leaves the trunk standing; it is a cap, not a shortcut. Pass collect:false only when speed matters more than the drops.\n"
                        + "- A named block id may also match a log building; pass structures:false to refuse one. A tag or no type never touches a building.\n"
                        + "- Chopping, mining and a tree search start around you: near is not needed when nobody is named. near \"me\" is only the player's own position. When the player points at a tree or a block, pass the coordinates they mean, not a moved search origin.");
        hint(
                metas,
                "mine_blocks",
                "- Use mine_blocks for a kind of block (ore, stone, a named log type); use fell_tree when the player asks for a tree.\n"
                        + "- When the player names no number (\"pick the flowers\", \"clear this sand away\", \"take the bushes\"), they mean all of them here: call mine_blocks with all_nearby:true and count 64, never count 1.\n"
                        + "- After placing blocks, mine_blocks with placed:true takes back only those blocks; it never touches an unplaced neighbour.\n"
                        + "- radius widens or narrows this call. exclude skips ids or tags. until_item/until_count stop once that many items were actually taken, e.g. until_item \"minecraft:raw_iron\" with until_count 16. require_tool:true refuses a block that needs a tool the companion does not carry.");
        hint(
                metas,
                "mine_block_at",
                "- Use mine_block_at when the owner points at one block: it mines that block and continues into its connected vein, then says how many it mined and what it took. Use mine_blocks to collect a number of blocks of a kind found around you; use fell_tree for a whole tree.\n"
                        + "- vein:false mines only the pointed block; vein_limit caps the vein. collect:false skips the drop sweep when speed matters.");
        hint(
                metas,
                "excavate",
                "- Use excavate for a shape: box (two corners in box), tunnel (run direction/length), stair (descends one block per step, always at least three blocks high), shaft (down/up) or strip (branches). One call covers the region; count stops early, until_item/until_count stop on taken items.\n"
                        + "- Protected places, containers and lava are never broken by default; containers:true is the only opt-in. Use clear_area to clear a plot for building, not to mine its solids.");
        hint(
                metas,
                "pickup_items",
                "- To pick up what fell around a block you placed, pass placed:true; the round is centred on that block, not on you.");
        hint(
                metas,
                "store_items",
                "- When the player says to store it again in the container you placed, pass placed:true; it uses the nearest container from your placement record.");
        hint(
                metas,
                "find_placed",
                "- Use find_placed when the player asks what you just placed or tells you to take those blocks back. "
                        + "mine_blocks, pickup_items and store_items take placed:true to act on your own placements.");
        hint(
                metas,
                "find_target",
                "- Use find_target when the player asks to find something that is not a material: a village or other structure, a biome, a villager or animal, or a place you know.\n"
                        + "- A structure is found by seeing its marker blocks, never by assuming where one is; the result names the budget spent and why it stopped.");
        hint(
                metas,
                "plan_patch",
                "- Use plan_patch only on an unstarted structured plan: name its route/index/remove and flat replacement steps, with the fingerprint from queue_show. A started plan is refused.");
        hint(
                metas,
                "replan_plan",
                "- Use replan_plan only for a stalled queued plan whose remaining cause the plan cannot obtain; it keeps completed work and patches the pending step.");
        hint(
                metas,
                "offer_handoff",
                "- Use offer_handoff only when the owner asks to give an exact item quantity to another owner at a named meeting point. Both owners must accept.");
        hint(
                metas,
                "accept_handoff",
                "- Use accept_handoff only when both companions are at the meeting point and both owners accepted. The exact quantity moves once.");
        hint(
                metas,
                "request_help",
                "- Use request_help only when the owner asks another owner's companion for one bounded piece of help. A request expires and acceptance authorizes only that help.");
        hint(
                metas,
                "accept_help",
                "- Use accept_help when the owner accepts the named request. It authorizes only the help that request names.");
        hint(
                metas,
                "decline_help",
                "- Use decline_help when the owner declines a request. No work or transfer happens.");
        hint(
                metas,
                "find_structure",
                "- Use find_structure when the player asks for a village, fortress, stronghold, bastion, ancient city or trial chamber. A structure is found by seeing its marker blocks, never by assuming where one is.");
        hint(
                metas,
                "search_structure",
                "- Use search_structure when the player asks for a village, fortress, stronghold, bastion, ancient city or trial chamber. A structure is found by seeing its marker blocks, never by assuming where one is.");
        hint(
                metas,
                "search_hint",
                "- Use search_hint only after look_around or survey_area gave you evidence: a direction, landmark, biome or rough area with one line of reason. It steers the next search; it never issues a waypoint, and a bad hint costs one leg.");
        hint(
                metas,
                "obtain_item",
                "- To get an item you don't have, prefer obtain_item over long hand-written plans; use plan_obtain to explain what it will take.\n"
                        + "- In creative mode, obtain_item can supply a registered item directly for an authorized owner.\n"
                        + "- For several items that share materials, use obtain_items so the raw materials are gathered once.");
        hint(
                metas,
                "obtain_items",
                "- For a set of items that share materials (a tool set, a set of builds), prefer obtain_items: it gathers every raw material once, then crafts them all.");
        hint(
                metas,
                "create_item",
                "- In creative mode, use create_item for resources instead of mining unnecessarily. It reports how many fit and keeps work bounded; choose owner only when the owner is nearby in the same dimension.");
        hint(
                metas,
                "fly_to",
                "- In creative mode, use fly_to for an inaccessible target or a gap the ground route cannot cross. It reports failure when no collision-safe route through loaded chunks exists.");
        hint(
                metas,
                "execute_command",
                "- In creative mode, execute_command accepts only allowlisted teleport/time/weather/item/effect/enchant forms and uses the owner's live command permissions. Use @s for yourself, give/enchant only for the owner, and describe the returned feedback without claiming a denied or failed command succeeded.");
        hint(
                metas,
                "build_structure",
                "- For buildings, prefer a generator for standard shapes, call blueprint_help for the format, and preview_structure before build_structure. preview_structure reports the parts (floor, walls, roof, entrance, window, interior); pass parts to build only some of them. A failed build stays half built at its origin: call build_structure again without coordinates to resume it there instead of choosing a new place.");
        hint(
                metas,
                "clear_area",
                "- Use clear_area to empty a plot before building: it clears vegetation and snow by default, takes water sources when it carries an empty bucket, and breaks solid blocks only with blocks=true. Chests, lava and protected places are never touched.");
        hint(
                metas,
                "set_follow_mode",
                "- \"Don't follow me\" means set_follow_mode with mode=off: it stops your idle walk and your work keeps running. Use stay only to wait in one place for a few minutes, and end_free_time only when the owner stops your free time.");
        hint(
                metas,
                "set_pace",
                "- To change how fast you travel, call set_pace: run when asked to hurry or run, walk when asked to slow down, auto to go back to the default.");
        hint(
                metas,
                "get_food",
                "- When you are hungry with no food: if the owner is near, ask them; otherwise call get_food.");
        hint(
                metas,
                "remember_place",
                "- When the owner teaches a place (\"remember this as home\"), call remember_place with the words they used; here:true stores their position, so do not guess coordinates.");
        group(metas, ToolGroup.AUTONOMY, Cost.LOW, "start_free_time", "end_free_time", "choose_activity");
        group(metas, ToolGroup.FIRE, Cost.MEDIUM, "extinguish_fire");
        hint(
                metas,
                "extinguish_fire",
                "- A fire is an emergency: call extinguish_fire at once for your own fire (target self), the owner's (owner), a burning mob (entity) or the fires around you (near). A water bucket is poured and taken back; without water a fire block is broken by hand. Never break a lit campfire — it is a cooking station — and never pour water in the nether.");
        hint(
                metas,
                "start_free_time",
                "- When the owner releases you (\"do your own thing\", \"do whatever you want\"), call start_free_time with their hint as wish; call end_free_time when they call you back.");
        hint(
                metas,
                "choose_activity",
                "- In a free-time event, pick the single most useful activity from the agenda and call choose_activity once; the planner writes the steps.");
        hint(
                metas,
                "deposit_items",
                "- To unload loot at the base, use deposit_items with the storage place name; it keeps your tools and food. Use store_items only for one container or one item.");
        hint(
                metas,
                "find_place",
                "- When a place name is uncertain, call find_place first, then go_to_place with the exact name from the result; never guess a name.");
        hint(
                metas,
                "find_traders",
                "- Trading knowledge belongs to the owner. Find only loaded nearby traders; remembered locations and offers can be stale.");
        hint(
                metas,
                "refresh_trades",
                "- Refresh at most 20 explicitly selected traders; only complete live inspection replaces remembered offers.");
        hint(
                metas,
                "wait_for_restock",
                "- Wait between checks with merchant sessions closed; only real vanilla restocks count, and no stock or workstation is forced.");
        hint(
                metas,
                "known_trades",
                "- Known prices are historical quotes. Check freshness and inspect a trader before any purchase; never promise a piglin barter reward.");
        hint(
                metas,
                "plan_trade",
                "- plan_trade is read-only: check exact stock, output batches and every required currency before placing an order.");
        hint(
                metas,
                "trade_item",
                "- Spend only for an explicit owner order with a cap and reserve for every input. Respect missing currency and explicit offer refs; never buy filler to level a villager.");
        hint(
                metas,
                "level_trader",
                "- Level only the exact selected villager, using only the owner's allowed offer refs under one shared cumulative cap. Stop at the requested level; never buy filler.");
        hint(
                metas,
                "barter",
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
