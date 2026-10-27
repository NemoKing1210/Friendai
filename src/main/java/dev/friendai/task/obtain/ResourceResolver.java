package dev.friendai.task.obtain;

import dev.friendai.crafting.Station;
import dev.friendai.crafting.obtain.Source;
import dev.friendai.knowledge.search.ResourceFamily;
import dev.friendai.task.crafting.RecipeIndex;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Resolves a player resource name to the block ids whose remembered sites can supply it. */
public final class ResourceResolver {
    private ResourceResolver() {}

    public record Resolution(Set<String> blockIds, String family, boolean executable, String reason) {
        public Resolution {
            blockIds = Set.copyOf(blockIds);
            if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
            if (reason == null) reason = "";
        }
    }

    public static Resolution resolve(ServerLevel level, String resource) {
        if (resource == null || resource.isBlank()) return new Resolution(Set.of(), "unknown", false, "resource is blank");
        String text = resource.strip();
        if (text.startsWith("#")) return resolveBlockTag(text);
        Identifier id = Identifier.tryParse(text);
        if (id == null) return new Resolution(Set.of(), text, false, "resource id is invalid");
        if (BuiltInRegistries.BLOCK.containsKey(id)) {
            String blockId = id.toString();
            if (crafted(level, blockId)) {
                return resolveItem(level, blockId);
            }
            return new Resolution(Set.of(blockId),
                    ResourceFamily.ofBlocks(Set.of(blockId)).orElse(blockId), true, "block id");
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return new Resolution(Set.of(), id.toString(), false, "unknown block tag, block id or item id");
        }
        return resolveItem(level, id.toString());
    }

    private static Resolution resolveBlockTag(String text) {
        Identifier id = Identifier.tryParse(text.substring(1));
        if (id == null) return new Resolution(Set.of(), text, false, "block tag id is invalid");
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
        Set<String> blocks = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.builtInRegistryHolder().is(tag)) blocks.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        String family = ResourceFamily.ofBlocks(blocks).orElse("#" + id);
        return new Resolution(blocks, family, !blocks.isEmpty(),
                blocks.isEmpty() ? "block tag has no registered members" : "block tag");
    }

    private static Resolution resolveItem(ServerLevel level, String itemId) {
        var drops = DropIndex.of(level);
        var recipes = RecipeIndex.of(level);
        Set<String> blocks = new LinkedHashSet<>();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<ItemDepth> pending = new ArrayDeque<>();
        pending.add(new ItemDepth(itemId, 0));
        boolean smelting = false;
        while (!pending.isEmpty()) {
            ItemDepth current = pending.removeFirst();
            if (!visited.add(current.id()) || current.depth() > 5) continue;
            boolean crafted = crafted(level, current.id());
            for (Source source : drops.sourcesOf(current.id())) {
                if (source instanceof Source.Mine mine) {
                    // A placed block of a crafted item is not the source of the item: follow the recipe instead.
                    if (!crafted || !mine.block().equals(current.id())) blocks.add(mine.block());
                }
                if (source instanceof Source.Harvest harvest) blocks.add(harvest.crop());
            }
            for (var recipe : recipes.producing(current.id())) {
                if (recipe.station() == Station.FURNACE || recipe.station() == Station.SMOKER
                        || recipe.station() == Station.BLAST_FURNACE || recipe.station() == Station.CAMPFIRE) smelting = true;
                if (current.depth() < 5) {
                    recipe.ingredients().forEach(ingredient -> ingredient.items().forEach(id ->
                            pending.addLast(new ItemDepth(id, current.depth() + 1))));
                }
            }
        }
        String reason = blocks.isEmpty() ? "no block source is indexed for this item"
                : smelting ? "source chain includes smelting" : "source chain resolved";
        String family = ResourceFamily.ofBlocks(blocks).orElse(itemId);
        return new Resolution(blocks, family, !blocks.isEmpty(), reason);
    }

    private static boolean crafted(ServerLevel level, String itemId) {
        return RecipeIndex.of(level).producing(itemId).stream().anyMatch(recipe -> !recipe.station().isCooking());
    }

    private record ItemDepth(String id, int depth) {}
}
