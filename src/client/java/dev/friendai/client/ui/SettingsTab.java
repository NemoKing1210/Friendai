package dev.friendai.client.ui;

import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * Per-companion settings as a list of cards: each has a title, a short description, an optional status line (why it
 * cannot be changed, what the companion is doing) and an on/off switch at the right. The mod's own configuration is
 * a separate screen.
 */
final class SettingsTab extends StatefulMenuTab {
    private static final int GAP = 4;
    private static final int PAD = 8;
    private static final int HEADER_HEIGHT = 32;
    private static final int SWITCH_WIDTH = 56;
    private static final int SWITCH_HEIGHT = 20;
    private static final int LINE = 10;
    private static final int MAX_DESCRIPTION_LINES = 2;

    private int left;
    private int top;
    private int width;
    private Font font;
    private Setting creativeMode;
    private Setting autoRespawn;
    private Setting freeTime;
    private Setting followMode;
    private Setting movementDebug;
    private List<Setting> all = List.of();

    /** One card: its texts, its switch and where the last layout put it. */
    private static final class Setting {
        final String key;
        final int accent;
        final FlatButton button;
        List<FormattedCharSequence> description = List.of();
        Component status;
        int statusColor = Ui.DIM;
        boolean visible = true;
        int y;
        int height;

        Setting(String key, int accent, FlatButton button) {
            this.key = key;
            this.accent = accent;
            this.button = button;
        }
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        this.left = left;
        this.top = top;
        this.width = width;
        this.font = host.font();
        creativeMode = setting(
                host,
                "friendai.menu.settings.creative.title",
                "friendai.menu.settings.creative.desc",
                Ui.PURPLE,
                this::toggleCreativeMode);
        autoRespawn = setting(
                host,
                "friendai.menu.settings.auto_respawn.title",
                "friendai.menu.settings.auto_respawn.desc",
                Ui.ACCENT,
                this::toggleAutoRespawn);
        freeTime = setting(
                host,
                "friendai.menu.settings.free_time.title",
                "friendai.menu.settings.free_time.desc",
                Ui.GOOD,
                () -> CompanionRequests.send(new ControlRequest.FreeTimeToggle()));
        followMode = setting(
                host,
                "friendai.menu.settings.follow.title",
                "friendai.menu.settings.follow.desc",
                Ui.ACCENT,
                this::toggleFollowMode);
        movementDebug = setting(
                host,
                "friendai.menu.settings.movement_debug.title",
                "friendai.menu.settings.movement_debug.desc",
                Ui.PURPLE,
                SettingsTab::toggleMovementDebug);
        all = List.of(creativeMode, autoRespawn, freeTime, followMode, movementDebug);
        tick();
    }

    private Setting setting(Host host, String titleKey, String descKey, int accent, Runnable action) {
        FlatButton button = host.add(new FlatButton(0, 0, SWITCH_WIDTH, SWITCH_HEIGHT, Component.empty(), 0, action));
        Setting setting = new Setting(titleKey, accent, button);
        int textWidth = width - SWITCH_WIDTH - 4 * PAD;
        List<FormattedCharSequence> lines = font.split(Component.translatable(descKey), textWidth);
        setting.description = lines.size() > MAX_DESCRIPTION_LINES ? lines.subList(0, MAX_DESCRIPTION_LINES) : lines;
        return setting;
    }

    private void toggleFollowMode() {
        state.snapshot()
                .ifPresent(
                        snapshot -> CompanionRequests.send(new ControlRequest.SetFollowMode(!snapshot.followMode())));
    }

    /** Session-only: the overlay is off whenever a connection starts, so it never switches itself on. */
    private static void toggleMovementDebug() {
        boolean on = !MovementDebugState.enabled();
        MovementDebugState.setEnabled(on);
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

        movementDebug.visible = operator;
        apply(movementDebug, MovementDebugState.enabled(), connected && operator);
        movementDebug.status = null;

        boolean hardcore = snapshot.map(s -> !s.autoRespawnAvailable()).orElse(false);
        autoRespawn.visible = present;
        apply(
                autoRespawn,
                snapshot.map(CompanionSnapshot::autoRespawn).orElse(false),
                connected && present && !hardcore);
        autoRespawn.status = hardcore ? Component.translatable("friendai.menu.settings.auto_respawn.hardcore") : null;
        autoRespawn.statusColor = Ui.WARN;

        boolean freeOn = snapshot.map(CompanionSnapshot::freeTimeOn).orElse(false);
        freeTime.visible = present;
        apply(freeTime, freeOn, connected && present);
        freeTime.status = snapshot.filter(s -> s.freeTimeOn())
                .map(s -> s.freeTimeActivity().isEmpty()
                        ? Component.translatable(
                                s.freeTimeWaiting()
                                        ? "friendai.menu.activity.free_waiting"
                                        : "friendai.menu.activity.free_on")
                        : Component.literal(TaskChips.humanize(s.freeTimeActivity())))
                .orElse(null);
        freeTime.statusColor = Ui.ACCENT;

        followMode.visible = present;
        apply(followMode, snapshot.map(CompanionSnapshot::followMode).orElse(false), connected && present);
        followMode.status = null;

        boolean creative = snapshot.map(CompanionSnapshot::creativeMode).orElse(false);
        boolean creativeAvailable =
                snapshot.map(CompanionSnapshot::creativeModeAvailable).orElse(false);
        creativeMode.visible = true;
        apply(creativeMode, creative, connected && present && creativeAvailable);
        creativeMode.status = !connected
                ? Component.translatable("friendai.menu.creative.connection")
                : !present
                        ? Component.translatable("friendai.menu.creative.no_companion")
                        : !creativeAvailable
                                ? Component.translatable("friendai.menu.creative.operator_required")
                                : null;
        creativeMode.statusColor = Ui.WARN;

        layout();
    }

    private static void apply(Setting setting, boolean on, boolean enabled) {
        setting.button.active = enabled;
        setting.button.setMessage(
                Component.translatable(on ? "friendai.menu.settings.on" : "friendai.menu.settings.off"));
        setting.button.setStatus(on ? Ui.GOOD : Ui.DIM);
    }

    /** Stacks the visible cards so a hidden one leaves no gap, and centres each switch in its card. */
    private void layout() {
        int y = top + HEADER_HEIGHT;
        for (Setting setting : all) {
            setting.button.visible = setting.visible;
            if (!setting.visible) {
                continue;
            }
            int text = 20 + setting.description.size() * LINE + (setting.status == null ? 0 : LINE);
            setting.height = Math.max(34, text + PAD - 2);
            setting.y = y;
            setting.button.setX(left + width - SWITCH_WIDTH - PAD);
            setting.button.setY(y + (setting.height - SWITCH_HEIGHT) / 2);
            y += setting.height + GAP;
        }
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Ui.heading(
                g,
                font,
                Component.translatable("friendai.menu.settings.heading"),
                left + PAD,
                top + PAD,
                width - 2 * PAD);
        String hint = Component.translatable("friendai.menu.settings.hint").getString();
        Ui.text(g, font, Ui.clip(font, hint, width - 2 * PAD), left + PAD, top + PAD + 12, Ui.DIM);
        for (Setting setting : all) {
            if (!setting.visible) {
                continue;
            }
            Ui.card(g, left, setting.y, width, setting.height);
            g.fill(
                    left,
                    setting.y,
                    left + 3,
                    setting.y + setting.height,
                    setting.button.active || setting.status == null ? setting.accent : Ui.DIM);
            int tx = left + PAD + 2;
            int ty = setting.y + 6;
            Ui.text(g, font, Component.translatable(setting.key), tx, ty, Ui.TEXT);
            ty += 12;
            for (FormattedCharSequence line : setting.description) {
                Ui.text(g, font, line, tx, ty, Ui.DIM);
                ty += LINE;
            }
            if (setting.status != null) {
                Ui.text(
                        g,
                        font,
                        Ui.clip(font, setting.status.getString(), width - SWITCH_WIDTH - 4 * PAD),
                        tx,
                        ty,
                        setting.statusColor);
            }
        }
    }
}
