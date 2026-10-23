package dev.friendai.client.ui;

import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.RequestType;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** What the companion sees, its prompt, and manual message and tool runs. */
final class DebugTab implements MenuTab {
    private static final int ROW = 22;

    private final ScrollText panel = new ScrollText(false);
    private boolean showPrompt;
    private String selectedTool = "";
    private String messageText = "";
    private String argsText = "";
    private EditBox message;
    private EditBox args;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int bottom = top + height;
        int sendWidth = 70;
        message = host.add(new EditBox(host.font(), left, bottom - 2 * ROW, width - sendWidth - 6, 20,
                Component.translatable("friendai.menu.debug.message_hint")));
        message.setMaxLength(500);
        message.setHint(Component.translatable("friendai.menu.debug.message_hint"));
        message.setValue(messageText);
        message.setResponder(s -> messageText = s);
        host.add(Button.builder(Component.translatable("friendai.menu.debug.send"), b -> {
            CompanionRequests.send(RequestType.SEND_MESSAGE, message.getValue(), "");
            message.setValue("");
        }).bounds(left + width - sendWidth, bottom - 2 * ROW, sendWidth, 20).build());

        int toolWidth = 130;
        int runWidth = 60;
        List<String> toolNames = CompanionClientState.snapshot().map(CompanionSnapshot::toolNames).orElse(List.of());
        if (!toolNames.contains(selectedTool)) {
            selectedTool = toolNames.isEmpty() ? "" : toolNames.get(0);
        }
        if (!toolNames.isEmpty()) {
            host.add(CycleButton.<String>builder(name -> Component.translatable("friendai.menu.debug.tool", name), selectedTool)
                    .withValues(toolNames)
                    .create(left, bottom - ROW, toolWidth, 20, Component.empty(), (button, value) -> selectedTool = value));
        }
        args = host.add(new EditBox(host.font(), left + toolWidth + 6, bottom - ROW, width - toolWidth - runWidth - 12, 20,
                Component.translatable("friendai.menu.debug.args_hint")));
        args.setMaxLength(2000);
        args.setHint(Component.translatable("friendai.menu.debug.args_hint"));
        args.setValue(argsText);
        args.setResponder(s -> argsText = s);
        host.add(Button.builder(Component.translatable("friendai.menu.debug.run"),
                b -> CompanionRequests.send(RequestType.RUN_TOOL, selectedTool, args.getValue()))
                .bounds(left + width - runWidth, bottom - ROW, runWidth, 20).build());
        host.add(Button.builder(Component.translatable("friendai.menu.debug.show_prompt"), b -> {
            showPrompt = true;
            CompanionRequests.send(RequestType.REQUEST_PROMPT);
        }).bounds(left, top, 120, 16).build());
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        var snapshot = CompanionClientState.snapshot();
        String body;
        if (showPrompt && !CompanionClientState.prompt().isEmpty()) {
            body = CompanionClientState.prompt();
        } else {
            g.text(font, Component.translatable("friendai.menu.debug.perception"), left + 128, top + 4, 0xFFAAAAAA);
            body = snapshot.map(CompanionSnapshot::perception).orElse("");
        }
        panel.setLines(List.of(body.split("\n", -1)));
        panel.render(g, font, left, top + 22, width, height - 22 - 2 * ROW - 4);
    }

    @Override
    public boolean scroll(double amount) {
        return panel.scroll(amount);
    }
}
