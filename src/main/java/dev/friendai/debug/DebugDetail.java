package dev.friendai.debug;

import java.util.Map;

/** Resolves built-in English task details to client translation keys without adding game dependencies to debug data. */
public record DebugDetail(String translationKey, String argument) {
    private static final Map<String, String> BUILT_IN = Map.ofEntries(
            Map.entry("Path obstacle", "friendai.debug.detail.path_obstacle"),
            Map.entry("Path support", "friendai.debug.detail.path_support"),
            Map.entry("Clear build site", "friendai.debug.detail.clear_build_site"),
            Map.entry("Build foundation", "friendai.debug.detail.build_foundation"),
            Map.entry("Take materials from storage", "friendai.debug.detail.take_materials"),
            Map.entry("Load furnace", "friendai.debug.detail.load_furnace"),
            Map.entry("Harvest crop", "friendai.debug.detail.harvest_crop"),
            Map.entry("Replant crop", "friendai.debug.detail.replant_crop"),
            Map.entry("Sow crop", "friendai.debug.detail.sow_crop"),
            Map.entry("Till soil", "friendai.debug.detail.till_soil"),
            Map.entry("Use bone meal", "friendai.debug.detail.use_bone_meal"),
            Map.entry("Cut log", "friendai.debug.detail.cut_log"),
            Map.entry("Place build block", "friendai.debug.detail.place_build_block"),
            Map.entry("Explore waypoint", "friendai.debug.detail.explore_waypoint"),
            Map.entry("Search excavation waypoint", "friendai.debug.detail.search_excavation_waypoint"),
            Map.entry("Search origin", "friendai.debug.detail.search_origin"),
            Map.entry("Collect finished items", "friendai.debug.detail.collect_finished"),
            Map.entry("container", "friendai.debug.detail.container"));

    public static DebugDetail from(String detail) {
        String value = detail == null ? "" : detail;
        String exact = BUILT_IN.get(value);
        if (exact != null) {
            return new DebugDetail(exact, "");
        }
        for (String[] prefix : new String[][] {
                {"Craft ", "friendai.debug.detail.craft"},
                {"Smelt ", "friendai.debug.detail.smelt"},
                {"Mine ", "friendai.debug.detail.mine"},
                {"Inspect ", "friendai.debug.detail.inspect"},
                {"Place ", "friendai.debug.detail.place"},
                {"Take back ", "friendai.debug.detail.take_back"},
                {"Collect ", "friendai.debug.detail.collect"}}) {

            if (value.startsWith(prefix[0])) {
                return new DebugDetail(prefix[1], value.substring(prefix[0].length()));
            }
        }
        return new DebugDetail("", value);
    }
}
