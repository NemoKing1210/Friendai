package dev.friedai.client.ui;

import dev.friedai.debug.CompanionSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The current task and the history of recent ones. */
final class TasksTab implements MenuTab {
    private final ScrollText history = new ScrollText(false);

    @Override
    public void init(Host host, int left, int top, int width, int height) {}

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        var snapshot = CompanionClientState.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        CompanionSnapshot s = snapshot.get();
        g.text(font, Component.translatable("friedai.menu.current_task", s.present() ? s.currentTask() : "-"),
                left, top, 0xFFFFFFFF);
        g.text(font, Component.translatable("friedai.menu.history"), left, top + 20, 0xFFAAAAAA);
        List<String> lines = new ArrayList<>();
        s.history().forEach(r -> lines.add("#" + r.id() + " " + r.toolName() + " - " + r.status() + " - " + r.summary()));
        if (lines.isEmpty()) {
            lines.add(Component.translatable("friedai.menu.history_empty").getString());
        }
        history.setLines(lines);
        history.render(g, font, left, top + 34, width, height - 34);
    }

    @Override
    public boolean scroll(double amount) {
        return history.scroll(amount);
    }
}
