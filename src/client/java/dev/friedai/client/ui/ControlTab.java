package dev.friedai.client.ui;

import dev.friedai.debug.CompanionSnapshot;
import dev.friedai.debug.RequestType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** Summon, dismiss and stop, plus a short status block. */
final class ControlTab implements MenuTab {
    private static final int BUTTON_WIDTH = 90;
    private static final int GAP = 6;

    private Button summon;
    private Button dismiss;
    private Button stop;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int y = top + 56;
        summon = host.add(Button.builder(Component.translatable("friedai.menu.summon"),
                b -> CompanionRequests.send(RequestType.SUMMON)).bounds(left, y, BUTTON_WIDTH, 20).build());
        dismiss = host.add(Button.builder(Component.translatable("friedai.menu.dismiss"),
                b -> CompanionRequests.send(RequestType.DISMISS)).bounds(left + BUTTON_WIDTH + GAP, y, BUTTON_WIDTH, 20).build());
        stop = host.add(Button.builder(Component.translatable("friedai.menu.stop"),
                b -> CompanionRequests.send(RequestType.STOP)).bounds(left + 2 * (BUTTON_WIDTH + GAP), y, BUTTON_WIDTH, 20).build());
        tick();
    }

    @Override
    public void tick() {
        boolean present = CompanionClientState.snapshot().map(CompanionSnapshot::present).orElse(false);
        boolean connected = CompanionRequests.serverSupported() && !CompanionClientState.isStale();
        summon.active = connected && !present;
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
            g.text(font, Component.translatable("friedai.menu.no_companion"), left, top, 0xFFFFFFFF);
            g.text(font, Component.translatable("friedai.menu.model", s.model(), s.provider()), left, top + 14, 0xFFAAAAAA);
            return;
        }
        g.text(font, Component.translatable("friedai.menu.companion", s.companionName()), left, top, 0xFFFFFFFF);
        g.text(font, Component.translatable("friedai.menu.model", s.model(), s.provider()), left, top + 14, 0xFFAAAAAA);
        g.text(font,
                Component.translatable(s.thinking() ? "friedai.menu.state.thinking" : "friedai.menu.state.idle"),
                left, top + 28, 0xFFAAAAAA);
        g.text(font, Component.translatable("friedai.menu.current_task", s.currentTask()), left, top + 42, 0xFFFFFFFF);
    }
}
