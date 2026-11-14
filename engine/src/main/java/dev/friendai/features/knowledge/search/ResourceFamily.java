package dev.friendai.features.knowledge.search;

import java.util.Optional;
import java.util.Set;

/** Classifies a set of block ids into the family a search profile understands, without reading the world. */
public final class ResourceFamily {
    public static final String ANY_WOOD = "#minecraft:logs";
    public static final String LEAVES = "#minecraft:leaves";
    public static final String CROPS = "#minecraft:crops";

    private ResourceFamily() {}

    /** The shared family of {@code blockIds}, or empty when the ids are mixed or unsupported. */
    public static Optional<String> ofBlocks(Set<String> blockIds) {
        if (blockIds == null || blockIds.isEmpty()) {
            return Optional.empty();
        }
        if (blockIds.stream().allMatch(ResourceFamily::isWoodBlock)) {
            return Optional.of(ANY_WOOD);
        }
        if (blockIds.stream().allMatch(ResourceFamily::isLeafBlock)) {
            return Optional.of(LEAVES);
        }
        if (blockIds.stream().allMatch(ResourceFamily::isCropBlock)) {
            return Optional.of(CROPS);
        }
        if (blockIds.stream().allMatch(ResourceFamily::isOreBlock)) {
            String family = oreFamily(blockIds.iterator().next());
            return blockIds.stream().allMatch(id -> oreFamily(id).equals(family))
                    ? Optional.of(family)
                    : Optional.empty();
        }
        if (blockIds.size() == 1) {
            return Optional.of(blockIds.iterator().next());
        }
        return Optional.empty();
    }

    /** True for a wood family: any log tag or a family named after a log, stem or hypha. */
    public static boolean isWood(String family) {
        if (family == null) return false;
        return family.equals(ANY_WOOD)
                || family.endsWith("_log")
                || family.endsWith("_wood")
                || family.endsWith("_stem")
                || family.endsWith("_hyphae");
    }

    /** True for a family a surface search can look for. */
    public static boolean isPlant(String family) {
        return isWood(family)
                || LEAVES.equals(family)
                || CROPS.equals(family)
                || isSurfaceVegetation(family)
                || family != null
                        && (family.endsWith("_leaves")
                                || family.endsWith("_sapling")
                                || family.endsWith("_flower")
                                || family.endsWith("_planks"));
    }

    /** Vegetation tags a walk over the surface finds: flowers, saplings and the plants trees may replace. */
    private static final Set<String> SURFACE_VEGETATION_TAGS = Set.of(
            "#minecraft:flowers",
            "#minecraft:small_flowers",
            "#minecraft:tall_flowers",
            "#minecraft:saplings",
            "#minecraft:replaceable_by_trees");

    /** Single surface plants by id path: what an owner asks to pick, cut or collect while walking about. */
    private static final Set<String> SURFACE_VEGETATION_PATHS = Set.of(
            "poppy",
            "dandelion",
            "blue_orchid",
            "allium",
            "azure_bluet",
            "oxeye_daisy",
            "cornflower",
            "lily_of_the_valley",
            "wither_rose",
            "torchflower",
            "sunflower",
            "lilac",
            "rose_bush",
            "peony",
            "pink_petals",
            "wildflowers",
            "dead_bush",
            "bush",
            "firefly_bush",
            "short_grass",
            "tall_grass",
            "short_dry_grass",
            "tall_dry_grass",
            "fern",
            "large_fern",
            "cactus",
            "sugar_cane",
            "sweet_berry_bush",
            "pumpkin",
            "melon",
            "bamboo");

    /**
     * True for a flower, bush, grass, cactus or another plant that grows on the surface, given as a tag, a family or
     * a block id. A walk over the surface is the only search that finds one; it never lies underground.
     */
    public static boolean isSurfaceVegetation(String family) {
        if (family == null) return false;
        if (SURFACE_VEGETATION_TAGS.contains(family)) return true;
        if (family.startsWith("#")) return false;
        String path = path(family);
        return SURFACE_VEGETATION_PATHS.contains(path) || path.endsWith("_tulip") || path.endsWith("_mushroom");
    }

    private static boolean isWoodBlock(String blockId) {
        String path = path(blockId);
        return path.endsWith("_log") || path.endsWith("_wood") || path.endsWith("_stem") || path.endsWith("_hyphae");
    }

    private static boolean isLeafBlock(String blockId) {
        return path(blockId).endsWith("_leaves");
    }

    private static boolean isCropBlock(String blockId) {
        String path = path(blockId);
        return path.equals("wheat")
                || path.equals("carrots")
                || path.equals("potatoes")
                || path.equals("beetroots")
                || path.equals("torchflower_crop")
                || path.equals("pitcher_crop")
                || path.equals("nether_wart")
                || path.endsWith("_crop");
    }

    private static boolean isOreBlock(String blockId) {
        return path(blockId).endsWith("_ore");
    }

    private static String oreFamily(String blockId) {
        String path = path(blockId);
        if (path.startsWith("deepslate_")) path = path.substring("deepslate_".length());
        return switch (path) {
            case "coal_ore" -> "minecraft:coal";
            case "redstone_ore" -> "minecraft:redstone";
            case "lapis_ore" -> "minecraft:lapis_lazuli";
            case "diamond_ore" -> "minecraft:diamond";
            case "emerald_ore" -> "minecraft:emerald";
            case "nether_quartz_ore" -> "minecraft:quartz";
            case "nether_gold_ore" -> "minecraft:raw_gold";
            default -> "minecraft:raw_" + path.substring(0, path.length() - "_ore".length());
        };
    }

    private static String path(String blockId) {
        int separator = blockId.indexOf(':');
        return separator < 0 ? blockId : blockId.substring(separator + 1);
    }
}
