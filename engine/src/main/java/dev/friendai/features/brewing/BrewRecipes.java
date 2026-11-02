package dev.friendai.features.brewing;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The deliberately bounded supported brewing catalog. Each target is data: the same awkward-potion base and one
 * vanilla ingredient. A target outside this map is unsupported and returns a typed refusal rather than guessing at
 * a recipe the world may not contain.
 *
 * <p>Ids are registry ids, not Java constants: the adapter resolves them with
 * {@code BuiltInRegistries.ITEM}/{@code Potion}. The catalog is static, immutable and small, so a lookup allocates
 * nothing.
 */
public final class BrewRecipes {
    /** Most targets the catalog may hold; the map itself is smaller and fixed. */
    public static final int MAX_TARGETS = 8;

    private static final String WATER = "minecraft:water";
    private static final String AWKWARD = "minecraft:awkward";

    private static final Map<String, BrewRecipe> RECIPES = build();

    private BrewRecipes() {}

    private static Map<String, BrewRecipe> build() {
        Map<String, BrewRecipe> recipes = new LinkedHashMap<>();
        add(recipes, "minecraft:strength", "minecraft:blaze_powder");
        add(recipes, "minecraft:healing", "minecraft:glistering_melon_slice");
        add(recipes, "minecraft:swiftness", "minecraft:sugar");
        add(recipes, "minecraft:fire_resistance", "minecraft:magma_cream");
        add(recipes, "minecraft:night_vision", "minecraft:golden_carrot");
        add(recipes, "minecraft:water_breathing", "minecraft:pufferfish");
        add(recipes, "minecraft:regeneration", "minecraft:ghast_tear");
        add(recipes, "minecraft:poison", "minecraft:spider_eye");
        if (recipes.size() > MAX_TARGETS) {
            throw new IllegalStateException("brewing catalog exceeds " + MAX_TARGETS);
        }
        return Map.copyOf(recipes);
    }

    private static void add(Map<String, BrewRecipe> recipes, String target, String ingredient) {
        recipes.put(
                target,
                new BrewRecipe(
                        target,
                        List.of(
                                new BrewStep(WATER, "minecraft:nether_wart", AWKWARD),
                                new BrewStep(AWKWARD, ingredient, target))));
    }

    /** The full chain from water, or empty for a target the catalog does not support. */
    public static Optional<BrewRecipe> find(String target) {
        if (target == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(RECIPES.get(target.strip().toLowerCase(Locale.ROOT)));
    }

    /** The supported target ids, in catalog order. */
    public static List<String> targets() {
        return List.copyOf(RECIPES.keySet());
    }
}
