package dev.friendai.features.mining.minecraft.mining;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.friendai.world.block.BlockMatcher;
import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.Test;

class BlockFilterTest {
    @Test
    void includeAndExcludeCompose() {
        assertTrue(new BlockFilter(matcher("stone", true), List.of()).test(null));
        assertFalse(new BlockFilter(matcher("stone", false), List.of()).test(null));
        assertFalse(new BlockFilter(matcher("stone", true), List.of(matcher("dirt", true))).test(null));
        assertTrue(new BlockFilter(matcher("stone", true), List.of(matcher("dirt", false))).test(null));
    }

    @Test
    void labelNamesTheExclusions() {
        assertEquals("stone", new BlockFilter(matcher("stone", true), List.of()).label());
        assertEquals(
                "stone except dirt, #logs",
                new BlockFilter(matcher("stone", true), List.of(matcher("dirt", true), matcher("#logs", true)))
                        .label());
    }

    @Test
    void includeLabelKeepsTheExclusionsOut() {
        BlockFilter filter = new BlockFilter(matcher("stone", true), List.of(matcher("dirt", true)));

        assertEquals("stone", filter.includeLabel());
        assertEquals("stone except dirt", filter.label());
    }

    @Test
    void parseRejectsBlankParts() {
        assertTrue(BlockFilter.parse("", List.of()).isEmpty());
        assertTrue(BlockFilter.parse("stone", List.of(" ")).isEmpty());
    }

    private static BlockMatcher matcher(String label, boolean result) {
        return new BlockMatcher() {
            @Override
            public boolean test(BlockState state) {
                return result;
            }

            @Override
            public String label() {
                return label;
            }
        };
    }
}
