package dev.friendai.client.ui;

import dev.friendai.presentation.companion.CompanionSnapshot;
import dev.friendai.protocol.control.ControlRequest;
import dev.friendai.protocol.debug.DebugRequest;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** What the companion sees, its prompt, and manual message and tool runs. */
final class DebugTab extends StatefulMenuTab {
    private static final int FIELD = 20;
    private static final int HEADING = 12;
    private static final int GAP = 4;

    private final ScrollView view = new ScrollView(false);
    private boolean showPrompt;
    private String selectedTool = "";
    private String messageText = "";
    private String argsText = "";
    private EditBox message;
    private EditBox args;
    private FlatButton perceptionButton;
    private FlatButton promptButton;
    private FlatButton toolButton;
    private List<String> toolNames = List.of();

    private String wrappedFor = "";
    private int wrappedWidth = -1;
    private List<FormattedCharSequence> wrapped = List.of();
    private int bodyBottomOffset;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int bottom = top + height;
        int sendWidth = 70;
        int toolY = bottom - FIELD;
        int messageY = toolY - HEADING - FIELD - GAP;
        bodyBottomOffset = bottom - (messageY - HEADING - GAP);

        message = host.add(new EditBox(
                host.font(),
                left,
                messageY,
                width - sendWidth - GAP,
                FIELD,
                Component.translatable("friendai.menu.debug.message_hint")));
        message.setMaxLength(500);
        message.setHint(Component.translatable("friendai.menu.debug.message_hint"));
        message.setValue(messageText);
        message.setResponder(s -> messageText = s);
        host.add(new FlatButton(
                left + width - sendWidth,
                messageY,
                sendWidth,
                FIELD,
                Component.translatable("friendai.menu.debug.send"),
                Ui.GOOD,
                () -> {
                    CompanionRequests.send(new ControlRequest.SendChat(message.getValue()));
                    message.setValue("");
                }));

        int toolWidth = Math.min(140, width / 3);
        int runWidth = 60;
        toolNames = state.snapshot().map(CompanionSnapshot::toolNames).orElse(List.of());
        if (!toolNames.contains(selectedTool)) {
            selectedTool = toolNames.isEmpty() ? "" : toolNames.get(0);
        }
        toolButton = host.add(new FlatButton(left, toolY, toolWidth, FIELD, toolLabel(), Ui.PURPLE, this::nextTool));
        toolButton.active = !toolNames.isEmpty();
        args = host.add(new EditBox(
                host.font(),
                left + toolWidth + GAP,
                toolY,
                width - toolWidth - runWidth - 2 * GAP,
                FIELD,
                Component.translatable("friendai.menu.debug.args_hint")));
        args.setMaxLength(2000);
        args.setHint(Component.translatable("friendai.menu.debug.args_hint"));
        args.setValue(argsText);
        args.setResponder(s -> argsText = s);
        host.add(new FlatButton(
                left + width - runWidth,
                toolY,
                runWidth,
                FIELD,
                Component.translatable("friendai.menu.debug.run"),
                Ui.WARN,
                () -> CompanionRequests.send(new ControlRequest.InvokeTool(selectedTool, args.getValue()))));

        int segment = Math.min(120, (width - GAP) / 2);
        perceptionButton = host.add(new FlatButton(
                left,
                top,
                segment,
                16,
                Component.translatable("friendai.menu.debug.perception"),
                Ui.ACCENT,
                () -> showPrompt = false));
        promptButton = host.add(new FlatButton(
                left + segment + GAP,
                top,
                segment,
                16,
                Component.translatable("friendai.menu.debug.show_prompt"),
                Ui.ACCENT,
                () -> {
                    showPrompt = true;
                    CompanionRequests.send(new DebugRequest.PromptGet());
                }));
    }

    private Component toolLabel() {
        return Component.translatable("friendai.menu.debug.tool", selectedTool);
    }

    private void nextTool() {
        if (toolNames.isEmpty()) {
            return;
        }
        selectedTool = toolNames.get((toolNames.indexOf(selectedTool) + 1) % toolNames.size());
        toolButton.setMessage(toolLabel());
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        int bottom = top + height;
        int toolY = bottom - FIELD;
        int messageY = toolY - HEADING - FIELD - GAP;
        Ui.text(g, font, Component.translatable("friendai.menu.debug.tool_section"), left, toolY - HEADING + 2, Ui.DIM);
        Ui.text(
                g,
                font,
                Component.translatable("friendai.menu.debug.message_hint"),
                left,
                messageY - HEADING + 2,
                Ui.DIM);
        perceptionButton.setStatus(showPrompt ? Ui.DIM : Ui.ACCENT);
        promptButton.setStatus(showPrompt ? Ui.ACCENT : Ui.DIM);

        int bodyTop = top + 16 + GAP;
        int bodyHeight = bottom - bodyBottomOffset - bodyTop;
        Ui.well(g, left, bodyTop, width, bodyHeight);
        var snapshot = state.snapshot();
        String body = showPrompt && !state.prompt().isEmpty()
                ? state.prompt()
                : snapshot.map(CompanionSnapshot::perception).orElse("");
        if (!showPrompt && snapshot.filter(CompanionSnapshot::operator).isPresent()) {
            String planner = snapshot.get().freeTimePlannerModel();
            if (!planner.isBlank()) {
                body = Component.translatable("friendai.menu.free_time.planner_model", planner)
                                .getString()
                        + (body.isBlank() ? "" : "\n" + body);
            }
        }
        int textWidth = width - 16;
        if (wrappedWidth != textWidth || !body.equals(wrappedFor)) {
            List<FormattedCharSequence> out = new ArrayList<>();
            for (String line : body.split("\n", -1)) {
                out.addAll(font.split(Component.literal(line), textWidth));
            }
            wrapped = out;
            wrappedFor = body;
            wrappedWidth = textWidth;
        }
        int lineHeight = font.lineHeight + 1;
        view.begin(g, left + 1, bodyTop + 1, width - 2, bodyHeight - 2, wrapped.size() * lineHeight + 8);
        int y = view.top() + 4;
        for (FormattedCharSequence line : wrapped) {
            if (view.visible(y, lineHeight)) {
                Ui.text(g, font, line, left + 8, y, Ui.ON_WELL);
            }
            y += lineHeight;
        }
        view.end(g);
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return view.contains(x, y) && view.scroll(amount);
    }
}
