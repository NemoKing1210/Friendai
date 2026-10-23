package dev.friendai.client.ui;

import dev.friendai.debug.LogEntry;
import dev.friendai.debug.LogKind;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

/** The brain log with a filter per entry kind. */
final class LogTab implements MenuTab {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Set<LogKind> enabled = EnumSet.allOf(LogKind.class);
    private final ScrollText text = new ScrollText(true);
    private final List<Button> toggles = new ArrayList<>();

    private static String kindKey(LogKind kind) {
        return switch (kind) {
            case USER -> "friendai.menu.log.kind.user";
            case ASSISTANT -> "friendai.menu.log.kind.assistant";
            case TOOL_CALL -> "friendai.menu.log.kind.tool_call";
            case TOOL_RESULT -> "friendai.menu.log.kind.tool_result";
            case ERROR -> "friendai.menu.log.kind.error";
            case TASK -> "friendai.menu.log.kind.task";
            case MANUAL -> "friendai.menu.log.kind.manual";
        };
    }

    private Component toggleLabel(LogKind kind) {
        return Component.translatable(kindKey(kind)).withStyle(enabled.contains(kind) ? ChatFormatting.GREEN : ChatFormatting.GRAY);
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        toggles.clear();
        LogKind[] kinds = LogKind.values();
        int buttonWidth = Math.min(64, (width - 70) / kinds.length);
        for (int i = 0; i < kinds.length; i++) {
            LogKind kind = kinds[i];
            Button[] self = new Button[1];
            self[0] = host.add(Button.builder(toggleLabel(kind), b -> {
                if (!enabled.add(kind)) {
                    enabled.remove(kind);
                }
                b.setMessage(toggleLabel(kind));
            }).bounds(left + i * buttonWidth, top, buttonWidth, 16).build());
            toggles.add(self[0]);
        }
        host.add(Button.builder(Component.translatable("friendai.menu.log.copy"), b -> copy())
                .bounds(left + width - 60, top, 60, 16).build());
    }

    private List<String> visibleLines() {
        List<String> lines = new ArrayList<>();
        for (LogEntry e : CompanionClientState.log().view(enabled)) {
            lines.add("[" + TIME.format(Instant.ofEpochMilli(e.timeMillis())) + "] "
                    + Component.translatable(kindKey(e.kind())).getString() + ": " + e.text());
        }
        return lines;
    }

    private void copy() {
        Minecraft.getInstance().keyboardHandler.setClipboard(String.join("\n", visibleLines()));
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        List<String> lines = visibleLines();
        if (lines.isEmpty()) {
            lines = List.of(Component.translatable("friendai.menu.log.empty").getString());
        }
        text.setLines(lines);
        text.render(g, font, left, top + 22, width, height - 22);
    }

    @Override
    public boolean scroll(double amount) {
        return text.scroll(amount);
    }
}
