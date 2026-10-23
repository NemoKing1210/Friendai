package dev.friendai.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

public class FriendaiGameTests {
    @GameTest
    public void serverStarts(GameTestHelper h) {
        h.succeed();
    }
}
