package dev.friedai.task.crafting;

import dev.friedai.crafting.Ingredient;
import dev.friedai.crafting.Recipe;
import dev.friedai.crafting.RecipeBook;
import dev.friedai.crafting.Station;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import net.minecraft.world.item.crafting.display.RecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.util.context.ContextMap;

/** The server's recipes, flattened into the pure model the planner works with. */
public final class RecipeIndex implements RecipeBook {
    private static final String CRAFTING_TABLE = "minecraft:crafting_table";
    private static final String STONECUTTER = "minecraft:stonecutter";
    private static final String SMITHING_TABLE = "minecraft:smithing_table";

    /** A datapack reload replaces the manager, so the cached index follows the key. */
    private static final Map<RecipeManager, RecipeIndex> CACHE = new WeakHashMap<>();

    private final Map<String, List<Recipe>> byOutput;
    private final Map<String, List<Recipe>> cookingByInput;
    private final Map<String, RecipeHolder<?>> holders;

    private RecipeIndex(
            Map<String, List<Recipe>> byOutput,
            Map<String, List<Recipe>> cookingByInput,
            Map<String, RecipeHolder<?>> holders) {
        this.byOutput = byOutput;
        this.cookingByInput = cookingByInput;
        this.holders = holders;
    }

    /** The index for the level's current recipe manager, built once per manager. */
    public static synchronized RecipeIndex of(ServerLevel level) {
        RecipeManager manager = level.recipeAccess();
        RecipeIndex cached = CACHE.get(manager);
        if (cached != null) {
            return cached;
        }
        RecipeIndex built = build(level, manager);
        CACHE.put(manager, built);
        return built;
    }

    @Override
    public List<Recipe> producing(String item) {
        return byOutput.getOrDefault(item, List.of());
    }

    /** Cooking recipes whose ingredient accepts this item, in furnace, blast furnace, smoker, campfire order. */
    public List<Recipe> cookingFrom(String inputItem) {
        return cookingByInput.getOrDefault(inputItem, List.of());
    }

    @Override
    public String stationItem(Station station) {
        return switch (station) {
            case INVENTORY -> null;
            case CRAFTING_TABLE -> CRAFTING_TABLE;
            case STONECUTTER -> STONECUTTER;
            case SMITHING_TABLE -> SMITHING_TABLE;
            case FURNACE -> "minecraft:furnace";
            case SMOKER -> "minecraft:smoker";
            case BLAST_FURNACE -> "minecraft:blast_furnace";
            case CAMPFIRE -> "minecraft:campfire";
        };
    }

    /** The real server recipe behind a pure recipe id. */
    public Optional<RecipeHolder<?>> holder(String recipeId) {
        return Optional.ofNullable(holders.get(recipeId));
    }

    private static RecipeIndex build(ServerLevel level, RecipeManager manager) {
        List<RecipeHolder<?>> sorted = new ArrayList<>(manager.getRecipes());
        sorted.sort(Comparator.comparing(holder -> pureId(holder)));
        ContextMap context = SlotDisplayContext.fromLevel(level);

        Map<String, List<Recipe>> byOutput = new LinkedHashMap<>();
        Map<String, List<Recipe>> cookingByInput = new LinkedHashMap<>();
        Map<String, RecipeHolder<?>> holders = new LinkedHashMap<>();

        for (RecipeHolder<?> holder : sorted) {
            if (holder.value() instanceof ShapedRecipe shaped) {
                boolean inventory = shaped.getWidth() <= 2 && shaped.getHeight() <= 2;
                add(holder, inventory ? Station.INVENTORY : Station.CRAFTING_TABLE, context, byOutput, holders);
            } else if (holder.value() instanceof ShapelessRecipe) {
                boolean inventory = holder.value().placementInfo().ingredients().size() <= 4;
                add(holder, inventory ? Station.INVENTORY : Station.CRAFTING_TABLE, context, byOutput, holders);
            }
        }
        for (RecipeHolder<?> holder : sorted) {
            if (holder.value() instanceof StonecutterRecipe) {
                add(holder, Station.STONECUTTER, context, byOutput, holders);
            }
        }
        for (RecipeHolder<?> holder : sorted) {
            if (holder.value() instanceof SmithingTransformRecipe) {
                add(holder, Station.SMITHING_TABLE, context, byOutput, holders);
            }
        }
        for (RecipeHolder<?> holder : sorted) {
            if (holder.value() instanceof AbstractCookingRecipe cooking) {
                Station station = cookingStation(cooking.getType());
                if (station != null) {
                    add(holder, station, context, byOutput, holders, cooking.cookingTime(), cookingByInput);
                }
            }
        }

        Map<String, List<Recipe>> frozen = new LinkedHashMap<>();
        byOutput.forEach((output, recipes) -> frozen.put(output, List.copyOf(recipes)));
        Map<String, List<Recipe>> frozenCooking = new LinkedHashMap<>();
        cookingByInput.forEach((input, recipes) -> frozenCooking.put(input, List.copyOf(recipes)));
        return new RecipeIndex(Map.copyOf(frozen), Map.copyOf(frozenCooking), Map.copyOf(holders));
    }

    /** The station a cooking recipe type runs on, or null for a type this mod does not cook with. */
    private static Station cookingStation(RecipeType<?> type) {
        if (type == RecipeType.SMELTING) {
            return Station.FURNACE;
        }
        if (type == RecipeType.BLASTING) {
            return Station.BLAST_FURNACE;
        }
        if (type == RecipeType.SMOKING) {
            return Station.SMOKER;
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return Station.CAMPFIRE;
        }
        return null;
    }

    private static void add(
            RecipeHolder<?> holder,
            Station station,
            ContextMap context,
            Map<String, List<Recipe>> byOutput,
            Map<String, RecipeHolder<?>> holders) {
        add(holder, station, context, byOutput, holders, 0, null);
    }

    private static void add(
            RecipeHolder<?> holder,
            Station station,
            ContextMap context,
            Map<String, List<Recipe>> byOutput,
            Map<String, RecipeHolder<?>> holders,
            int cookTicks,
            Map<String, List<Recipe>> cookingByInput) {
        if (holder.value().isSpecial() || holder.value().placementInfo().isImpossibleToPlace()) {
            return;
        }
        List<Ingredient> ingredients = ingredients(holder);
        if (ingredients == null) {
            return;
        }
        ItemStack result = result(holder, context);
        if (result.isEmpty()) {
            return;
        }
        String id = pureId(holder);
        String output = BuiltInRegistries.ITEM.getKey(result.getItem()).toString();
        Recipe recipe = new Recipe(id, output, result.getCount(), ingredients, station, cookTicks);
        byOutput.computeIfAbsent(output, key -> new ArrayList<>()).add(recipe);
        if (cookingByInput != null) {
            for (Ingredient ingredient : ingredients) {
                for (String input : ingredient.items()) {
                    cookingByInput.computeIfAbsent(input, key -> new ArrayList<>()).add(recipe);
                }
            }
        }
        holders.put(id, holder);
    }

    private static String pureId(RecipeHolder<?> holder) {
        return holder.id().identifier().toString();
    }

    /** The recipe's slots in pattern order, or null when a slot accepts nothing. */
    private static List<Ingredient> ingredients(RecipeHolder<?> holder) {
        if (holder.value() instanceof SmithingTransformRecipe smithing) {
            List<Ingredient> ingredients = new ArrayList<>(3);
            List<Optional<net.minecraft.world.item.crafting.Ingredient>> slots = List.of(
                    smithing.templateIngredient(),
                    Optional.of(smithing.baseIngredient()),
                    smithing.additionIngredient());
            for (Optional<net.minecraft.world.item.crafting.Ingredient> slot : slots) {
                if (slot.isEmpty()) {
                    continue;
                }
                Ingredient ingredient = ingredient(slot.get());
                if (ingredient == null) {
                    return null;
                }
                ingredients.add(ingredient);
            }
            return ingredients;
        }
        List<Ingredient> ingredients = new ArrayList<>();
        for (net.minecraft.world.item.crafting.Ingredient slot : holder.value().placementInfo().ingredients()) {
            Ingredient ingredient = ingredient(slot);
            if (ingredient == null) {
                return null;
            }
            ingredients.add(ingredient);
        }
        return ingredients;
    }

    /** One slot, or null when the source ingredient accepts no items. */
    private static Ingredient ingredient(net.minecraft.world.item.crafting.Ingredient source) {
        if (source.isEmpty()) {
            return null;
        }
        Set<String> ids = new LinkedHashSet<>();
        source.items().forEach(item -> ids.add(itemId(item)));
        if (ids.isEmpty()) {
            return null;
        }
        if (source.display() instanceof SlotDisplay.TagSlotDisplay tagDisplay) {
            Optional<TagKey<Item>> tag = tagDisplay.tag().unwrapKey();
            if (tag.isPresent()) {
                return Ingredient.tag(tag.get().location().toString(), ids.toArray(String[]::new));
            }
        }
        String first = ids.iterator().next();
        return ids.size() == 1 ? Ingredient.of(first) : new Ingredient(ids, first + " or …");
    }

    private static String itemId(Holder<Item> holder) {
        return holder.unwrapKey()
                .map(key -> key.identifier().toString())
                .orElseGet(() -> BuiltInRegistries.ITEM.getKey(holder.value()).toString());
    }

    private static ItemStack result(RecipeHolder<?> holder, ContextMap context) {
        List<RecipeDisplay> displays = holder.value().display();
        if (displays.isEmpty()) {
            return ItemStack.EMPTY;
        }
        return displays.getFirst().result().resolveForFirstStack(context);
    }
}
