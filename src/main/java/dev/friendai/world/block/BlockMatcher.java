package dev.friendai.world.block;

import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Matches blocks by id ("minecraft:oak_log") or by tag ("#minecraft:logs"). */
public interface BlockMatcher {
    boolean test(BlockState state);

    /** The id or #tag this matcher was built from, normalized with its namespace. */
    String label();

    static Optional<BlockMatcher> parse(String idOrTag) {
        if (idOrTag == null || idOrTag.isBlank()) {
            return Optional.empty();
        }
        String text = idOrTag.strip();
        boolean isTag = text.startsWith("#");
        Identifier id = Identifier.tryParse(isTag ? text.substring(1) : text);
        if (id == null) {
            return Optional.empty();
        }
        if (isTag) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, id);
            return Optional.of(new BlockMatcher() {
                @Override
                public boolean test(BlockState state) {
                    return state.is(tag);
                }

                @Override
                public String label() {
                    return "#" + id;
                }
            });
        }
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        Block block = BuiltInRegistries.BLOCK.getValue(id);
        return Optional.of(new BlockMatcher() {
            @Override
            public boolean test(BlockState state) {
                return state.is(block);
            }

            @Override
            public String label() {
                return id.toString();
            }
        });
    }

    /** Like {@link #parse}, but a single block id that is air or a fluid is not a mining target. */
    static Optional<BlockMatcher> parseTarget(String idOrTag) {
        Optional<BlockMatcher> parsed = parse(idOrTag);
        if (parsed.isEmpty() || idOrTag.strip().startsWith("#")) {
            return parsed;
        }
        Identifier id = Identifier.tryParse(parsed.get().label());
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return Optional.empty();
        }
        BlockState state = BuiltInRegistries.BLOCK.getValue(id).defaultBlockState();
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return Optional.empty();
        }
        return parsed;
    }
}
