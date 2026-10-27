package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DebugDetailTest {
    @Test
    void mapsBuiltInActionDetailsToLocalizedKeys() {
        assertEquals(new DebugDetail("friendai.debug.detail.build_foundation", ""),
                DebugDetail.from("Build foundation"));
        assertEquals(new DebugDetail("friendai.debug.detail.load_furnace", ""),
                DebugDetail.from("Load furnace"));
        assertEquals(new DebugDetail("friendai.debug.detail.till_soil", ""),
                DebugDetail.from("Till soil"));
    }

    @Test
    void preservesDynamicDetailWhileLocalizingTheAction() {
        assertEquals(new DebugDetail("friendai.debug.detail.craft", "minecraft:wooden_pickaxe"),
                DebugDetail.from("Craft minecraft:wooden_pickaxe"));
    }
}
