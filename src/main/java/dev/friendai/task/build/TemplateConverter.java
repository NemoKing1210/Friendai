package dev.friendai.task.build;

import dev.friendai.build.BlockSpec;
import dev.friendai.build.Blueprint;
import dev.friendai.build.ParseResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/**
 * Turns a structure template (a {@code .nbt} file from a data pack, like {@code minecraft:village/plains/houses/...})
 * into a blueprint. Structure void and liquids are skipped, jigsaw blocks become their final state, and block-entity
 * data and entities are dropped: the companion places blocks empty.
 */
public final class TemplateConverter {
    private TemplateConverter() {}

    public static ParseResult convert(String id, ServerLevel level) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) {
            return ParseResult.failed(List.of("not a valid template id: " + id));
        }
        CompoundTag tag;
        try {
            Optional<StructureTemplate> template = level.getServer()
                    .getStructureTemplateManager()
                    .get(identifier);
            if (template.isEmpty()) {
                return ParseResult.failed(List.of("no structure template " + id));
            }
            tag = template.get().save(new CompoundTag());
        } catch (RuntimeException e) {
            return malformed(identifier);
        }
        try {
            return fromTag(identifier, tag, level);
        } catch (RuntimeException e) {
            return malformed(identifier);
        }
    }

    private static ParseResult fromTag(Identifier identifier, CompoundTag tag, ServerLevel level) {
        ListTag blockTags = tag.getListOrEmpty("blocks");
        if (blockTags.isEmpty()) {
            return ParseResult.failed(List.of("the blueprint has no blocks"));
        }
        int[] min = minimumCorner(blockTags);
        if (min == null) {
            return malformed(identifier);
        }

        int[] size = readSize(tag);
        int width = size[0];
        int height = size[1];
        int depth = size[2];
        if (width <= 0 || height <= 0 || depth <= 0) {
            return malformed(identifier);
        }
        if (width > Blueprint.MAX_SIZE || height > Blueprint.MAX_SIZE || depth > Blueprint.MAX_SIZE) {
            return ParseResult.failed(List.of(
                    "size " + width + "×" + height + "×" + depth + " exceeds " + Blueprint.MAX_SIZE));
        }

        BlockState[] palette = readPalette(tag, level);
        if (palette == null) {
            return malformed(identifier);
        }

        List<BlockSpec> specs = new ArrayList<>(blockTags.size());
        int liquids = 0;
        for (int i = 0; i < blockTags.size(); i++) {
            CompoundTag block = blockTags.getCompoundOrEmpty(i);
            ListTag pos = block.getListOrEmpty("pos");
            int stateIndex = block.getIntOr("state", -1);
            if (pos.size() != 3 || stateIndex < 0 || stateIndex >= palette.length) {
                return malformed(identifier);
            }
            BlockState state = palette[stateIndex];
            if (state.is(Blocks.STRUCTURE_VOID)) {
                continue;
            }
            if (!state.getFluidState().isEmpty()) {
                liquids++;
                continue;
            }
            String text = state.is(Blocks.JIGSAW) ? finalState(block) : BlockStateParser.serialize(state);
            if (text == null) {
                continue;
            }
            specs.add(new BlockSpec(
                    pos.getIntOr(0, 0) - min[0],
                    pos.getIntOr(1, 0) - min[1],
                    pos.getIntOr(2, 0) - min[2],
                    text));
        }

        if (specs.isEmpty()) {
            return ParseResult.failed(List.of("the blueprint has no blocks"));
        }
        if (specs.size() > Blueprint.MAX_BLOCKS) {
            return ParseResult.failed(List.of(
                    "block count " + specs.size() + " exceeds " + Blueprint.MAX_BLOCKS));
        }
        specs.sort((a, b) -> {
            if (a.y() != b.y()) {
                return Integer.compare(a.y(), b.y());
            }
            if (a.z() != b.z()) {
                return Integer.compare(a.z(), b.z());
            }
            return Integer.compare(a.x(), b.x());
        });

        List<String> notes = liquids == 0
                ? List.of()
                : List.of("skipped " + liquids + (liquids == 1 ? " liquid block" : " liquid blocks"));
        return ParseResult.ok(new Blueprint(name(identifier), width, height, depth, specs), notes);
    }

    /** The template's recorded size; zero when the tag is missing or unusable. */
    private static int[] readSize(CompoundTag tag) {
        ListTag sizeTag = tag.getList("size").orElse(null);
        if (sizeTag == null || sizeTag.size() != 3) {
            return new int[3];
        }
        return new int[] {sizeTag.getIntOr(0, 0), sizeTag.getIntOr(1, 0), sizeTag.getIntOr(2, 0)};
    }

    /** The template palette as block states, or null when an entry cannot be read. */
    private static BlockState[] readPalette(CompoundTag tag, ServerLevel level) {
        ListTag paletteTag = tag.getListOrEmpty("palette");
        HolderLookup<Block> blocks = level.holderLookup(Registries.BLOCK);
        BlockState[] palette = new BlockState[paletteTag.size()];
        for (int i = 0; i < paletteTag.size(); i++) {
            try {
                palette[i] = NbtUtils.readBlockState(blocks, paletteTag.getCompoundOrEmpty(i));
            } catch (RuntimeException e) {
                return null;
            }
        }
        return palette;
    }

    /** The smallest x, y, z over all block entries, or null when an entry has no usable position. */
    private static int[] minimumCorner(ListTag blockTags) {
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE};
        for (int i = 0; i < blockTags.size(); i++) {
            ListTag pos = blockTags.getCompoundOrEmpty(i).getListOrEmpty("pos");
            if (pos.size() != 3) {
                return null;
            }
            for (int axis = 0; axis < 3; axis++) {
                min[axis] = Math.min(min[axis], pos.getIntOr(axis, 0));
            }
        }
        return min;
    }

    /** The block a jigsaw turns into, as a state string; null when the template gives no usable final state. */
    private static String finalState(CompoundTag block) {
        Optional<CompoundTag> nbt = block.getCompound("nbt");
        if (nbt.isEmpty()) {
            return null;
        }
        Optional<String> text = nbt.get().getString("final_state");
        if (text.isEmpty()) {
            return null;
        }
        try {
            return BlockStateParser.serialize(
                    BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text.get(), false)
                            .blockState());
        } catch (Exception e) {
            return null;
        }
    }

    private static String name(Identifier identifier) {
        String path = identifier.getPath();
        int slash = path.lastIndexOf('/');
        return slash < 0 ? path : path.substring(slash + 1);
    }

    private static ParseResult malformed(Identifier identifier) {
        return ParseResult.failed(List.of("invalid structure template " + identifier));
    }
}
