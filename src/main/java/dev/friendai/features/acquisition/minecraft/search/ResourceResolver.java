package dev.friendai.features.acquisition.minecraft.search;

import dev.friendai.features.acquisition.RecipeChain;
import dev.friendai.features.crafting.minecraft.RecipeIndex;
import dev.friendai.features.knowledge.search.ResourceFamily;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

/** Resolves a player resource name to the block ids whose remembered sites can supply it. */
public final class ResourceResolver {
    private ResourceResolver() {}

    /**
     * The blocks that can supply the resource, and how thoroughly the recipe walk covered it. A partial result keeps
     * the blocks found so far; its reason names the bound that cut the walk.
     */
    public record Resolution(
            Set<String> blockIds, String family, boolean executable, String reason, RecipeChain.Coverage coverage) {
        public Resolution {
            blockIds = Set.copyOf(blockIds);
            if (family == null || family.isBlank()) throw new IllegalArgumentException("family must not be blank");
            if (reason == null) reason = "";
            if (coverage == null) {
                coverage = executable ? RecipeChain.Coverage.COMPLETE : RecipeChain.Coverage.UNSUPPORTED;
            }
        }

        public Resolution(Set<String> blockIds, String family, boolean executable, String reason) {
            this(blockIds, family, executable, reason, null);
        }

        public boolean partial() {
            return coverage == RecipeChain.Coverage.PARTIAL;
        }
    }

    public static Resolution resolve(ServerLevel level, String resource) {
        return resolve(level, resource, RecipeChain.Policy.DEFAULT);
    }

    /** Resolves with an explicit walk policy; the default is what a resource lookup uses. */
    public static Resolution resolve(ServerLevel level, String resource, RecipeChain.Policy policy) {
        if (resource == null || resource.isBlank())
            return new Resolution(Set.of(), "unknown", false, "resource is blank");
        String text = resource.strip();
        if (text.startsWith("#")) return resolveBlockTag(text);
        Identifier id = Identifier.tryParse(text);
        if (id == null) return new Resolution(Set.of(), text, false, "resource id is invalid");
        if (BuiltInRegistries.BLOCK.containsKey(id)) {
            String blockId = id.toString();
            if (crafted(level, blockId)) {
                return resolveItem(level, blockId, policy);
            }
            return new Resolution(
                    Set.of(blockId), ResourceFamily.ofBlocks(Set.of(blockId)).orElse(blockId), true, "block id");
        }
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            return new Resolution(Set.of(), id.toString(), false, "unknown block tag, block id or item id");
        }
        return resolveItem(level, id.toString(), policy);
    }

    private static Resolution resolveBlockTag(String text) {
        Identifier id = Identifier.tryParse(text.substring(1));
        if (id == null) return new Resolution(Set.of(), text, false, "block tag id is invalid");
        TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
        Set<String> blocks = new LinkedHashSet<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (block.builtInRegistryHolder().is(tag))
                blocks.add(BuiltInRegistries.BLOCK.getKey(block).toString());
        }
        String family = ResourceFamily.ofBlocks(blocks).orElse("#" + id);
        return new Resolution(
                blocks,
                family,
                !blocks.isEmpty(),
                blocks.isEmpty() ? "block tag has no registered members" : "block tag");
    }

    private static Resolution resolveItem(ServerLevel level, String itemId, RecipeChain.Policy policy) {
        RecipeChain.Result chain = RecipeChain.resolve(itemId, RecipeIndex.of(level), DropIndex.of(level), policy);
        String reason;
        if (!chain.executable()) {
            reason = "no block source is indexed for this item";
        } else if (chain.coverage() == RecipeChain.Coverage.PARTIAL) {
            reason = "source chain resolved partially: " + chain.limit() + " cut the search short, so a deeper source "
                    + "may exist";
        } else {
            reason = chain.smelting() ? "source chain includes smelting" : "source chain resolved";
        }
        String family = ResourceFamily.ofBlocks(chain.blockIds()).orElse(itemId);
        return new Resolution(chain.blockIds(), family, chain.executable(), reason, chain.coverage());
    }

    private static boolean crafted(ServerLevel level, String itemId) {
        return RecipeIndex.of(level).producing(itemId).stream()
                .anyMatch(recipe -> !recipe.station().isCooking());
    }
}
