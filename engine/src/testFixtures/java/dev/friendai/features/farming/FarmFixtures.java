package dev.friendai.features.farming;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds planner inputs from ASCII grids and a small crop book, so planner tests read like a picture of the field. */
public final class FarmFixtures {
    public static final String WHEAT = "minecraft:wheat";
    public static final String WHEAT_SEEDS = "minecraft:wheat_seeds";
    public static final String CARROT = "minecraft:carrot";
    public static final String POTATO = "minecraft:potato";
    public static final String BEETROOT_SEEDS = "minecraft:beetroot_seeds";
    public static final String NETHER_WART = "minecraft:nether_wart";

    private FarmFixtures() {}

    /** Wheat, carrots, potatoes, beetroots and nether wart, in that plantable order; all yield 1.0. */
    public static CropBook book() {
        return book(1.0);
    }

    public static CropBook book(double wheatSeedYield) {
        return CropBook.of(List.of(
                new CropInfo("minecraft:wheat", WHEAT_SEEDS, WHEAT, 7, Soil.FARMLAND, 0, wheatSeedYield, 1.0),
                new CropInfo("minecraft:carrots", CARROT, CARROT, 7, Soil.FARMLAND, 3, 1.0, 1.0),
                new CropInfo("minecraft:potatoes", POTATO, POTATO, 7, Soil.FARMLAND, 1, 1.0, 1.0),
                new CropInfo(
                        "minecraft:beetroots", BEETROOT_SEEDS, "minecraft:beetroot", 3, Soil.FARMLAND, 1, 1.0, 1.0),
                new CropInfo("minecraft:nether_wart", NETHER_WART, NETHER_WART, 3, Soil.SOUL_SAND, 0, 1.0, 1.0)));
    }

    /**
     * Parses a grid (row index = dz, column = dx, dy = 0): R ripe wheat, r ripe carrot, N ripe nether wart,
     * F ripe fruit (no replant), g growing wheat age 3, k growing carrot age 3, 1-6 growing wheat of that age,
     * . empty farmland, S empty soul sand, t tillable, # blocked, space = no cell.
     */
    public static FarmSnapshot field(Map<String, Integer> inventory, String... rows) {
        List<FieldCell> cells = new ArrayList<>();
        for (int dz = 0; dz < rows.length; dz++) {
            for (int dx = 0; dx < rows[dz].length(); dx++) {
                FieldCell cell = cell(rows[dz].charAt(dx), dx, dz);
                if (cell != null) {
                    cells.add(cell);
                }
            }
        }
        return new FarmSnapshot(cells, inventory, false, 0, true, FoodNeed.NONE);
    }

    public static FarmSnapshot withHoe(FarmSnapshot s, boolean hasHoe) {
        return new FarmSnapshot(s.cells(), s.inventory(), hasHoe, s.boneMeal(), s.chestSpace(), s.foodNeed());
    }

    public static FarmSnapshot withBoneMeal(FarmSnapshot s, int boneMeal) {
        return new FarmSnapshot(s.cells(), s.inventory(), s.hasHoe(), boneMeal, s.chestSpace(), s.foodNeed());
    }

    public static FarmSnapshot withFoodNeed(FarmSnapshot s, FoodNeed need) {
        return new FarmSnapshot(s.cells(), s.inventory(), s.hasHoe(), s.boneMeal(), s.chestSpace(), need);
    }

    private static FieldCell cell(char symbol, int dx, int dz) {
        return switch (symbol) {
            case 'R' -> new FieldCell(dx, 0, dz, CellKind.RIPE, "minecraft:wheat", WHEAT_SEEDS, 7, null);
            case 'r' -> new FieldCell(dx, 0, dz, CellKind.RIPE, "minecraft:carrots", CARROT, 7, null);
            case 'N' -> new FieldCell(dx, 0, dz, CellKind.RIPE, "minecraft:nether_wart", NETHER_WART, 3, null);
            case 'F' -> new FieldCell(dx, 0, dz, CellKind.RIPE, "minecraft:melon", null, 0, null);
            case 'g' -> new FieldCell(dx, 0, dz, CellKind.GROWING, "minecraft:wheat", null, 3, null);
            case 'k' -> new FieldCell(dx, 0, dz, CellKind.GROWING, "minecraft:carrots", null, 3, null);
            case '1', '2', '3', '4', '5', '6' ->
                new FieldCell(dx, 0, dz, CellKind.GROWING, "minecraft:wheat", null, symbol - '0', null);
            case '.' -> new FieldCell(dx, 0, dz, CellKind.EMPTY_SOIL, null, null, 0, Soil.FARMLAND);
            case 'S' -> new FieldCell(dx, 0, dz, CellKind.EMPTY_SOIL, null, null, 0, Soil.SOUL_SAND);
            case 't' -> new FieldCell(dx, 0, dz, CellKind.TILLABLE, null, null, 0, null);
            case '#' -> new FieldCell(dx, 0, dz, CellKind.BLOCKED, null, null, 0, null);
            case ' ' -> null;
            default -> throw new IllegalArgumentException("unknown field symbol: " + symbol);
        };
    }
}
