package dev.friendai.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/** The read-only settings command must work from the server console, where there is no player. */
public final class SettingsCommandTests {
    @GameTest
    public void settingsCommandReportsWithoutAPlayer(GameTestHelper h) throws Exception {
        var server = h.getLevel().getServer();
        int result = server.getCommands().getDispatcher().execute("friendai settings", server.createCommandSourceStack());
        h.assertTrue(result == 1, "/friendai settings returned " + result);
        h.succeed();
    }
}
