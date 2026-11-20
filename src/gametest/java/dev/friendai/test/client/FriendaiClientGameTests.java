package dev.friendai.test.client;

import dev.friendai.client.FriendaiClient;
import dev.friendai.client.ui.CompanionControlScreen;
import dev.friendai.client.ui.CompanionRequests;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.gui.screens.PauseScreen;

/** Runs the full release checks or a core launch with both optional integrations absent. */
public final class FriendaiClientGameTests implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        boolean withoutOptionalMods = Boolean.getBoolean("friendai.gametest.noOptionalMods");
        FabricLoader loader = FabricLoader.getInstance();
        if (loader.isModLoaded("yet_another_config_lib_v3") == withoutOptionalMods
                || loader.isModLoaded("modmenu") == withoutOptionalMods) {
            throw new AssertionError("Unexpected optional mod presence for the client test mode");
        }
        if (withoutOptionalMods) {
            openCoreMenu(context);
        } else {
            new SettingsScreenTests().runTest(context);
            new ReleaseConnectionTests().runTest(context);
        }
    }

    private static void openCoreMenu(ClientGameTestContext context) {
        try (var world = context.worldBuilder().create()) {
            context.waitFor(client -> CompanionRequests.serverSupported(), 400);
            context.runOnClient(client -> client.setScreenAndShow(new PauseScreen(true)));
            context.waitForScreen(PauseScreen.class);
            if (!context.tryClickScreenButton("friendai.menu.button")) {
                throw new AssertionError("The companion button is missing without optional integrations");
            }
            context.waitForScreen(CompanionControlScreen.class);
            context.waitFor(
                    client -> FriendaiClient.companionSession()
                            .viewState()
                            .snapshot()
                            .isPresent(),
                    200);
        }
        System.out.println("FRIENDAI_CLIENT PASS: negotiated core menu without YACL and Mod Menu");
    }
}
