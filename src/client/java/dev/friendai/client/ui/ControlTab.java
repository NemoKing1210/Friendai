package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.RequestType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** Companion actions and a short status block. */
final class ControlTab implements MenuTab {
    private static final int BUTTON_WIDTH = 90;
    private static final int GAP = 6;

    private Button summon;
    private Button teleport;
    private Button dismiss;
    private Button stop;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int y = top + 56;
        Component summonLabel = Component.translatable("friendai.menu.summon");
        Component teleportLabel = Component.translatable("friendai.menu.teleport");
        Component dismissLabel = Component.translatable("friendai.menu.dismiss");
        Component stopLabel = Component.translatable("friendai.menu.stop");
        int labelWidth = Math.max(Math.max(host.font().width(summonLabel), host.font().width(teleportLabel)),
                Math.max(host.font().width(dismissLabel), host.font().width(stopLabel)));
        int buttonWidth = Math.min(Math.max(1, (width - GAP) / 2), Math.max(BUTTON_WIDTH, labelWidth + 16));
        int secondColumn = left + buttonWidth + GAP;
        summon = addButton(host, summonLabel, RequestType.SUMMON, left, y, buttonWidth);
        teleport = addButton(host, teleportLabel, RequestType.TELEPORT, secondColumn, y, buttonWidth);
        dismiss = addButton(host, dismissLabel, RequestType.DISMISS, left, y + 24, buttonWidth);
        stop = addButton(host, stopLabel, RequestType.STOP, secondColumn, y + 24, buttonWidth);
        tick();
    }

    private static Button addButton(Host host, Component label, RequestType request, int x, int y, int width) {
        Button.Builder builder = Button.builder(label, b -> CompanionRequests.send(request)).bounds(x, y, width, 20);
        if (host.font().width(label) + 16 > width) {
            builder.tooltip(Tooltip.create(label));
        }
        return host.add(builder.build());
    }

    @Override
    public void tick() {
        boolean present = CompanionClientState.snapshot().map(CompanionSnapshot::present).orElse(false);
        boolean connected = CompanionRequests.serverSupported() && !CompanionClientState.isStale();
        summon.active = connected && !present;
        teleport.active = connected && present;
        dismiss.active = connected && present;
        stop.active = connected && present;
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        var snapshot = CompanionClientState.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        CompanionSnapshot s = snapshot.get();
        if (!s.present()) {
            g.text(font, Component.translatable("friendai.menu.no_companion"), left, top, 0xFFFFFFFF);
            g.text(font, Component.translatable("friendai.menu.model", s.model(), s.provider()), left, top + 14, 0xFFAAAAAA);
            return;
        }
        g.text(font, Component.translatable("friendai.menu.companion", s.companionName()), left, top, 0xFFFFFFFF);
        g.text(font, Component.translatable("friendai.menu.model", s.model(), s.provider()), left, top + 14, 0xFFAAAAAA);
        g.text(font,
                Component.translatable(s.thinking() ? "friendai.menu.state.thinking" : "friendai.menu.state.idle"),
                left, top + 28, 0xFFAAAAAA);
        g.text(font, Component.translatable("friendai.menu.current_task", s.currentTask()), left, top + 42, 0xFFFFFFFF);
    }
}
