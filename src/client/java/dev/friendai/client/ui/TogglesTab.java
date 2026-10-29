package dev.friendai.client.ui;

import dev.friendai.client.FriendaiClient;
import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/**
 * Per-companion switches: creative mode, auto respawn, free time and the movement overlay.
 * The mod's own settings are a separate screen; nothing here is called "settings".
 */
final class TogglesTab extends StatefulMenuTab {
    private static final int GAP = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int PAD = 8;
    private static final int HEADER_HEIGHT = 32;

    private int left;
    private int top;
    private FlatButton creativeMode;
    private FlatButton autoRespawn;
    private FlatButton freeTime;
    private FlatButton followMode;
    private FlatButton movementDebug;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        int y = top + HEADER_HEIGHT;
        creativeMode = host.add(
                new FlatButton(left, y, width, BUTTON_HEIGHT, Component.empty(), Ui.PURPLE, this::toggleCreativeMode));
        y += BUTTON_HEIGHT + GAP;
        autoRespawn = host.add(
                new FlatButton(left, y, width, BUTTON_HEIGHT, Component.empty(), Ui.ACCENT, this::toggleAutoRespawn));
        y += BUTTON_HEIGHT + GAP;
        freeTime = host.add(new FlatButton(
                left,
                y,
                width,
                BUTTON_HEIGHT,
                Component.empty(),
                Ui.GOOD,
                () -> CompanionRequests.send(new ControlRequest.FreeTimeToggle())));
        y += BUTTON_HEIGHT + GAP;
        followMode = host.add(
                new FlatButton(left, y, width, BUTTON_HEIGHT, Component.empty(), Ui.ACCENT, this::toggleFollowMode));
        y += BUTTON_HEIGHT + GAP;
        movementDebug = host.add(new FlatButton(
                left, y, width, BUTTON_HEIGHT, Component.empty(), Ui.PURPLE, TogglesTab::toggleMovementDebug));
        tick();
    }

    private void toggleFollowMode() {
        state.snapshot()
                .ifPresent(
                        snapshot -> CompanionRequests.send(new ControlRequest.SetFollowMode(!snapshot.followMode())));
    }

    private static void toggleMovementDebug() {
        boolean on = !MovementDebugState.enabled();
        MovementDebugState.setEnabled(on);
        FriendaiClient.clientConfig().debug.movementOverlay = on;
        FriendaiClient.runtime().configuration().save(FriendaiClient.clientConfig());
        CompanionRequests.send(new DebugRequest.MovementStreamSet(on));
    }

    private void toggleAutoRespawn() {
        state.snapshot()
                .ifPresent(
                        snapshot -> CompanionRequests.send(new ControlRequest.SetAutoRespawn(!snapshot.autoRespawn())));
    }

    private void toggleCreativeMode() {
        state.snapshot()
                .ifPresent(snapshot ->
                        CompanionRequests.send(new ControlRequest.SetCreativeMode(!snapshot.creativeMode())));
    }

    @Override
    public void tick() {
        var snapshot = state.snapshot();
        boolean present = snapshot.map(CompanionSnapshot::present).orElse(false);
        boolean connected = CompanionRequests.serverSupported() && !state.isStale();

        boolean operator = snapshot.map(CompanionSnapshot::operator).orElse(false);
        if (connected && operator && MovementDebugState.preferenceRestorePending()) {
            boolean preferred = FriendaiClient.clientConfig().debug.movementOverlay;
            MovementDebugState.restorePreference(preferred);
            if (preferred) CompanionRequests.send(new DebugRequest.MovementStreamSet(true));
        }

        movementDebug.visible = operator;
        movementDebug.active = connected;
        movementDebug.setMessage(Component.translatable(
                MovementDebugState.enabled() ? "friendai.menu.debug.on" : "friendai.menu.debug.off"));

        autoRespawn.visible = present;
        autoRespawn.active = connected
                && present
                && snapshot.map(CompanionSnapshot::autoRespawnAvailable).orElse(false);
        autoRespawn.setMessage(Component.translatable(snapshot.map(s -> !s.autoRespawnAvailable()
                        ? "friendai.menu.auto_respawn.hardcore"
                        : s.autoRespawn() ? "friendai.menu.auto_respawn.on" : "friendai.menu.auto_respawn.off")
                .orElse("friendai.menu.auto_respawn.off")));

        freeTime.visible = present;
        freeTime.active = connected && present;
        freeTime.setMessage(snapshot.map(s -> !s.freeTimeOn()
                        ? Component.translatable("friendai.menu.free_time.off")
                        : s.freeTimeActivity().isEmpty()
                                ? Component.translatable(
                                        s.freeTimeWaiting()
                                                ? "friendai.menu.free_time.waiting"
                                                : "friendai.menu.free_time.on")
                                : Component.translatable("friendai.menu.free_time.activity", s.freeTimeActivity()))
                .orElse(Component.translatable("friendai.menu.free_time.off")));

        followMode.visible = present;
        followMode.active = connected && present;
        followMode.setMessage(Component.translatable(
                snapshot.map(s -> s.followMode() ? "friendai.menu.follow.on" : "friendai.menu.follow.off")
                        .orElse("friendai.menu.follow.off")));

        boolean creative = snapshot.map(CompanionSnapshot::creativeMode).orElse(false);
        boolean creativeAvailable =
                snapshot.map(CompanionSnapshot::creativeModeAvailable).orElse(false);
        creativeMode.visible = true;
        creativeMode.active = connected && present && creativeAvailable;
        creativeMode.setMessage(
                Component.translatable(creative ? "friendai.menu.creative.on" : "friendai.menu.creative.off"));
        String tooltipKey = !connected
                ? "friendai.menu.creative.connection"
                : !present
                        ? "friendai.menu.creative.no_companion"
                        : !creativeAvailable
                                ? "friendai.menu.creative.operator_required"
                                : creative ? "friendai.menu.creative.disable" : "friendai.menu.creative.enable";
        creativeMode.setTooltip(Tooltip.create(Component.translatable(tooltipKey)));

        layout();
    }

    /** Stacks the visible switches so a hidden one leaves no gap. */
    private void layout() {
        int y = top + HEADER_HEIGHT;
        for (FlatButton control : new FlatButton[] {creativeMode, autoRespawn, freeTime, followMode, movementDebug}) {
            if (!control.visible) {
                continue;
            }
            control.setX(left);
            control.setY(y);
            y += BUTTON_HEIGHT + GAP;
        }
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Ui.heading(
                g,
                font,
                Component.translatable("friendai.menu.toggles.heading"),
                left + PAD,
                top + PAD,
                width - 2 * PAD);
        String hint = Component.translatable("friendai.menu.toggles.hint").getString();
        Ui.text(g, font, Ui.clip(font, hint, width - 2 * PAD), left + PAD, top + PAD + 12, Ui.DIM);
    }
}
