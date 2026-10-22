package dev.friedai.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FriedaiGameTests {
    @GameTest
    public void serverStarts(GameTestHelper h) {
        h.succeed();
    }
}
