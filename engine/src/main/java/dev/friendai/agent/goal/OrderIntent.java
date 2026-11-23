package dev.friendai.agent.goal;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The measurable part of an owner's order: what must reach the owner, what the companion keeps, and which parts a
 * named structure must contain. Only shapes with a deterministic reading are recognised; everything else is left to
 * free-form criteria and reported as unresolved rather than guessed.
 */
public final class OrderIntent {
    public static final int MAX_ITEMS = 16;
    public static final int MAX_TEXT = 400;

    private static final Map<String, String> PLAIN_ITEMS = Map.ofEntries(
            Map.entry("cobblestone", "minecraft:cobblestone"),
            Map.entry("stone", "minecraft:stone"),
            Map.entry("dirt", "minecraft:dirt"),
            Map.entry("sand", "minecraft:sand"),
            Map.entry("gravel", "minecraft:gravel"),
            Map.entry("coal", "minecraft:coal"),
            Map.entry("charcoal", "minecraft:charcoal"),
            Map.entry("diamond", "minecraft:diamond"),
            Map.entry("emerald", "minecraft:emerald"),
            Map.entry("stick", "minecraft:stick"),
            Map.entry("bread", "minecraft:bread"),
            Map.entry("wheat", "minecraft:wheat"),
            Map.entry("carrot", "minecraft:carrot"),
            Map.entry("potato", "minecraft:potato"),
            Map.entry("apple", "minecraft:apple"),
            Map.entry("string", "minecraft:string"),
            Map.entry("wool", "minecraft:white_wool"),
            Map.entry("torch", "minecraft:torch"),
            Map.entry("bucket", "minecraft:bucket"),
            Map.entry("iron_ingot", "minecraft:iron_ingot"),
            Map.entry("gold_ingot", "minecraft:gold_ingot"),
            Map.entry("copper_ingot", "minecraft:copper_ingot"));

    private static final Map<String, String> WOOD_LOGS = Map.ofEntries(
            Map.entry("oak", "minecraft:oak_log"),
            Map.entry("birch", "minecraft:birch_log"),
            Map.entry("spruce", "minecraft:spruce_log"),
            Map.entry("jungle", "minecraft:jungle_log"),
            Map.entry("acacia", "minecraft:acacia_log"),
            Map.entry("dark_oak", "minecraft:dark_oak_log"),
            Map.entry("mangrove", "minecraft:mangrove_log"),
            Map.entry("cherry", "minecraft:cherry_log"));

    private static final Map<String, List<String>> TOOL_SETS = Map.of(
            "stone",
            List.of(
                    "minecraft:stone_pickaxe",
                    "minecraft:stone_axe",
                    "minecraft:stone_shovel",
                    "minecraft:stone_sword"),
            "wooden",
            List.of(
                    "minecraft:wooden_pickaxe",
                    "minecraft:wooden_axe",
                    "minecraft:wooden_shovel",
                    "minecraft:wooden_sword"),
            "iron",
            List.of("minecraft:iron_pickaxe", "minecraft:iron_axe", "minecraft:iron_shovel", "minecraft:iron_sword"),
            "diamond",
            List.of(
                    "minecraft:diamond_pickaxe",
                    "minecraft:diamond_axe",
                    "minecraft:diamond_shovel",
                    "minecraft:diamond_sword"),
            "golden",
            List.of(
                    "minecraft:golden_pickaxe",
                    "minecraft:golden_axe",
                    "minecraft:golden_shovel",
                    "minecraft:golden_sword"),
            "netherite",
            List.of(
                    "minecraft:netherite_pickaxe",
                    "minecraft:netherite_axe",
                    "minecraft:netherite_shovel",
                    "minecraft:netherite_sword"));

    private static final Set<String> STRUCTURES =
            Set.of("house", "hut", "cabin", "shelter", "wall", "tower", "bridge", "platform", "fence");

    private static final Map<String, String> PARTS = Map.of(
            "roof", "roof",
            "walls", "walls",
            "wall", "walls",
            "floor", "floor",
            "door", "entrance",
            "doorway", "entrance",
            "entrance", "entrance",
            "window", "window",
            "windows", "window");

    /** One item and how many of it the owner must receive and the companion must keep. */
    public record ItemNeed(String item, int ownerCount, int companionCount) {
        public ItemNeed {
            if (item == null || item.isBlank()) throw new IllegalArgumentException("item");
            if (ownerCount < 0 || companionCount < 0 || ownerCount + companionCount < 1) {
                throw new IllegalArgumentException("counts");
            }
        }
    }

    /** A structure the order names, with the parts it explicitly asked for. */
    public record BuildNeed(String structure, Set<String> parts) {
        public BuildNeed {
            parts = Set.copyOf(parts == null ? Set.of() : parts);
        }
    }

    private final String text;
    private final List<ItemNeed> items;
    private final List<BuildNeed> builds;
    private final List<String> unresolved;
    private final boolean possession;

    private OrderIntent(
            String text, List<ItemNeed> items, List<BuildNeed> builds, List<String> unresolved, boolean possession) {
        this.text = text;
        this.items = List.copyOf(items);
        this.builds = List.copyOf(builds);
        this.unresolved = List.copyOf(unresolved);
        this.possession = possession;
    }

    public static OrderIntent parse(String order) {
        String normalized = order == null ? "" : order.strip();
        if (normalized.length() > MAX_TEXT) normalized = normalized.substring(0, MAX_TEXT);
        List<String> tokens = tokens(normalized);
        String joined = String.join(" ", tokens);
        boolean possession = joined.contains("do i have")
                || joined.contains("check that")
                || joined.contains("how many")
                || joined.contains("do i own");
        boolean eachOfUs = joined.contains("each of us")
                || joined.contains("both of us")
                || joined.contains("you and me")
                || joined.contains("for us");
        boolean toOwner = !possession
                && (joined.contains("give me")
                        || joined.contains("bring me")
                        || joined.contains("to me")
                        || joined.contains("for me")
                        || joined.contains("hand me")
                        || joined.contains("deliver"));
        int keep = keepCount(tokens);

        List<ItemNeed> items = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<String> unresolved = new ArrayList<>();
        boolean[] consumed = new boolean[tokens.size()];
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (TOOL_SETS.containsKey(token) && setWordNearby(tokens, i)) {
                int scale = countBefore(tokens, i);
                for (String item : TOOL_SETS.get(token)) {
                    addNeed(items, seen, item, scale, eachOfUs, toOwner, keep, possession);
                }
                consumed[i] = true;
                for (int j = i + 1; j < Math.min(tokens.size(), i + 4); j++) consumed[j] = true;
            }
        }
        for (int i = 0; i < tokens.size(); i++) {
            if (consumed[i]) continue;
            String token = tokens.get(i);
            String item = itemId(tokens, i);
            if (item == null) continue;
            int count = countBefore(tokens, i);
            addNeed(items, seen, item, count, eachOfUs, toOwner, keep, possession);
            consumed[i] = true;
        }
        List<BuildNeed> builds = new ArrayList<>();
        int structureAt = -1;
        for (int i = 0; i < tokens.size(); i++) {
            if (STRUCTURES.contains(tokens.get(i)) && joined.contains("build")) {
                structureAt = i;
                break;
            }
        }
        if (structureAt >= 0) {
            Set<String> parts = new LinkedHashSet<>();
            for (String token : tokens) {
                String part = PARTS.get(token);
                if (part != null) parts.add(part);
            }
            builds.add(new BuildNeed(tokens.get(structureAt), parts));
        }
        if (items.isEmpty() && builds.isEmpty() && hasOrderVerb(joined)) {
            unresolved.add(
                    "the order has no measurable item, quantity or structure; state the criteria the world can check");
        }
        return new OrderIntent(normalized, items, builds, unresolved, possession);
    }

    private static void addNeed(
            List<ItemNeed> items,
            Set<String> seen,
            String item,
            int count,
            boolean eachOfUs,
            boolean toOwner,
            int keep,
            boolean possession) {
        if (items.size() >= MAX_ITEMS || !seen.add(item)) return;
        int owner;
        int companion;
        if (keep >= 0) {
            companion = Math.min(keep, count);
            owner = Math.max(0, count - companion);
        } else if (possession) {
            owner = count;
            companion = 0;
        } else if (eachOfUs) {
            owner = count;
            companion = count;
        } else if (toOwner) {
            owner = count;
            companion = 0;
        } else {
            owner = 0;
            companion = count;
        }
        if (owner == 0 && companion == 0) return;
        items.add(new ItemNeed(item, owner, companion));
    }

    private static boolean setWordNearby(List<String> tokens, int index) {
        for (int i = index + 1; i < Math.min(tokens.size(), index + 4); i++) {
            String next = tokens.get(i);
            if (next.equals("tool") || next.equals("tools") || next.equals("kit") || next.equals("set")) {
                return true;
            }
        }
        return false;
    }

    private static String itemId(List<String> tokens, int index) {
        String token = tokens.get(index);
        if (token.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) return token;
        String plain = singular(token);
        if (PLAIN_ITEMS.containsKey(plain)) return PLAIN_ITEMS.get(plain);
        if ((plain.equals("log") || plain.equals("logs"))
                && index > 0
                && WOOD_LOGS.containsKey(tokens.get(index - 1))) {
            return WOOD_LOGS.get(tokens.get(index - 1));
        }
        if (WOOD_LOGS.containsKey(plain) && index + 1 < tokens.size()) {
            String next = singular(tokens.get(index + 1));
            if (next.equals("log") || next.equals("logs")) return WOOD_LOGS.get(plain);
        }
        return null;
    }

    private static String singular(String token) {
        if (token.endsWith("es") && token.length() > 3) return token.substring(0, token.length() - 2);
        if (token.endsWith("s") && token.length() > 2) return token.substring(0, token.length() - 1);
        return token;
    }

    private static int countBefore(List<String> tokens, int index) {
        for (int i = index - 1; i >= Math.max(0, index - 3); i--) {
            String token = tokens.get(i);
            if (token.equals("a") || token.equals("an") || token.equals("the")) continue;
            Integer number = number(token);
            if (number != null) return number;
            break;
        }
        return 1;
    }

    private static int keepCount(List<String> tokens) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            if (!tokens.get(i).equals("keep")) continue;
            Integer number = number(tokens.get(i + 1));
            if (number != null) return number;
        }
        return -1;
    }

    private static Integer number(String token) {
        if (token.matches("[0-9]{1,3}")) return Integer.parseInt(token);
        return switch (token) {
            case "one" -> 1;
            case "two" -> 2;
            case "three" -> 3;
            case "four" -> 4;
            case "five" -> 5;
            case "six" -> 6;
            case "seven" -> 7;
            case "eight" -> 8;
            case "nine" -> 9;
            case "ten" -> 10;
            case "eleven" -> 11;
            case "twelve" -> 12;
            case "twenty" -> 20;
            default -> null;
        };
    }

    private static boolean hasOrderVerb(String joined) {
        for (String verb : List.of("give", "bring", "make", "craft", "gather", "get", "build", "collect", "mine")) {
            if (joined.contains(verb)) return true;
        }
        return false;
    }

    private static List<String> tokens(String text) {
        List<String> tokens = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9_:]+")) {
            if (!token.isBlank()) tokens.add(token);
        }
        return tokens;
    }

    public String text() {
        return text;
    }

    public List<ItemNeed> items() {
        return items;
    }

    public List<BuildNeed> builds() {
        return builds;
    }

    public List<String> unresolved() {
        return unresolved;
    }

    public boolean possession() {
        return possession;
    }

    public boolean recognised() {
        return !items.isEmpty() || !builds.isEmpty();
    }
}
