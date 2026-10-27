package dev.friendai.agent.autonomy;

import dev.friendai.crafting.obtain.Tier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The wood, stone, iron, diamond ladder a survival player climbs; each stage is data, not code. */
public final class ProgressionLadder {
    /**
     * What a stage asks for. A tool is met by its tier or better; armor needs {@code armorPieces} pieces of
     * {@code armor} or better.
     *
     * @param tools tool kind ({@code pickaxe}, {@code axe}, {@code sword}) to the minimum tier
     */
    public record Stage(String name, Map<String, Tier> tools, Tier armor, int armorPieces, boolean shield,
                        boolean bucket, boolean craftingTable, boolean furnace, Map<StockKind, Integer> stock) {}

    public static final List<Stage> STAGES = List.of(
            new Stage("WOOD", tools("pickaxe", Tier.WOOD), Tier.NONE, 0, false, false, true, false,
                    stock(StockKind.LOGS, 16)),
            new Stage("STONE", tools("pickaxe", Tier.STONE, "axe", Tier.STONE, "sword", Tier.STONE), Tier.NONE, 0,
                    false, false, true, true,
                    stock(StockKind.COBBLE, 32, StockKind.COAL, 16, StockKind.TORCHES, 32)),
            new Stage("IRON", tools("pickaxe", Tier.IRON, "sword", Tier.IRON), Tier.IRON, 2, true, true, true, true,
                    stock(StockKind.IRON_INGOT, 16)),
            new Stage("DIAMOND", tools("pickaxe", Tier.DIAMOND, "sword", Tier.DIAMOND), Tier.IRON, 2, true, true,
                    true, true, stock(StockKind.DIAMOND, 5)));

    private ProgressionLadder() {}

    public static LadderView evaluate(AutonomyFacts f) {
        for (Stage stage : STAGES) {
            LadderView view = check(stage, f);
            if (!view.complete()) {
                return view;
            }
        }
        return new LadderView(STAGES.getLast().name(), List.of(), List.of());
    }

    private static LadderView check(Stage stage, AutonomyFacts f) {
        List<String> gear = new ArrayList<>();
        stage.tools().forEach((kind, tier) -> {
            if (have(f, kind).ordinal() < tier.ordinal()) {
                gear.add(material(tier) + " " + kind);
            }
        });
        if (stage.armorPieces() > 0
                && (f.armor().ordinal() < stage.armor().ordinal() || f.armorPieces() < stage.armorPieces())) {
            int pieces = f.armor().ordinal() >= stage.armor().ordinal() ? f.armorPieces() : 0;
            gear.add(material(stage.armor()) + " armor " + pieces + "/" + stage.armorPieces() + " pieces");
        }
        if (stage.shield() && !f.shield()) {
            gear.add("shield");
        }
        if (stage.bucket() && !f.bucket()) {
            gear.add("bucket");
        }
        if (stage.craftingTable() && !f.craftingTable()) {
            gear.add("crafting table");
        }
        if (stage.furnace() && !f.furnace()) {
            gear.add("furnace");
        }
        List<String> stock = new ArrayList<>();
        stage.stock().forEach((kind, target) -> {
            if (f.count(kind) < target) {
                stock.add(kind.words() + " " + f.count(kind) + "/" + target);
            }
        });
        return new LadderView(stage.name(), gear, stock);
    }

    private static Tier have(AutonomyFacts f, String kind) {
        return switch (kind) {
            case "pickaxe" -> f.pickaxe();
            case "axe" -> f.axe();
            case "sword" -> f.sword();
            default -> Tier.NONE;
        };
    }

    private static String material(Tier tier) {
        return switch (tier) {
            case WOOD -> "wooden";
            case STONE -> "stone";
            case IRON -> "iron";
            case DIAMOND -> "diamond";
            case NETHERITE -> "netherite";
            case NONE -> "any";
        };
    }

    private static Map<String, Tier> tools(Object... pairs) {
        Map<String, Tier> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Tier) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }

    private static Map<StockKind, Integer> stock(Object... pairs) {
        Map<StockKind, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((StockKind) pairs[i], (Integer) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(map);
    }
}
