package dev.friendai.client.ui;

import com.google.gson.JsonObject;
import dev.friendai.features.instructions.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.network.chat.Component;

/** A paged owner catalogue, with pending approval and source visible before editing. */
final class SkillsTab extends StatefulMenuTab {
    private final SkillEditorModel model = new SkillEditorModel();
    private final List<Button> rows = new ArrayList<>();
    private final List<Button> actions = new ArrayList<>();
    private int offset, sourceFilter, scopeFilter, stateFilter;
    private Button sourceButton, scopeButton, stateButton;
    private String selected = "", deleteArmed = "", lastExport = "";
    private int right, width;

    static void open() {
        CompanionRequests.skills("open", "{}");
    }

    private void queryPage(int page) {
        JsonObject o = new JsonObject();
        o.addProperty("page", page);
        CompanionRequests.skills("page", o.toString());
    }

    private void select(String id) {
        JsonObject o = new JsonObject();
        o.addProperty("id", id);
        CompanionRequests.skills("detail", o.toString());
    }

    private Component title(SkillView.Row row) {
        return BuiltinSkills.titleKey(row.id())
                .map(Component::translatable)
                .orElseGet(() -> Component.literal(row.name()));
    }

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        this.width = width;
        this.right = left + Math.max(110, width / 3) + 8;
        rows.clear();
        actions.clear();
        int filterWidth = (width - 8) / 3;
        sourceButton = host.add(Button.builder(Component.translatable("friendai.menu.skills.filter_source"), b -> {
                    sourceFilter = (sourceFilter + 1) % 4;
                    offset = 0;
                })
                .bounds(left, top, filterWidth, 20)
                .build());
        scopeButton = host.add(Button.builder(Component.translatable("friendai.menu.skills.filter_scope"), b -> {
                    scopeFilter = (scopeFilter + 1) % 3;
                    offset = 0;
                })
                .bounds(left + filterWidth + 4, top, filterWidth, 20)
                .build());
        stateButton = host.add(Button.builder(Component.translatable("friendai.menu.skills.filter_state"), b -> {
                    stateFilter = (stateFilter + 1) % 3;
                    offset = 0;
                })
                .bounds(left + 2 * (filterWidth + 4), top, filterWidth, 20)
                .build());
        int n = Math.max(1, (height - 82) / 23), listWidth = right - left - 8;
        for (int i = 0; i < n; i++) {
            final int slot = i;
            rows.add(host.add(Button.builder(Component.empty(), b -> {
                        var list = filtered();
                        int index = offset + slot;
                        if (index >= list.size()) return;
                        selected = list.get(index).id();
                        deleteArmed = "";
                        model.select(selected);
                        select(selected);
                    })
                    .bounds(left, top + 27 + i * 23, listWidth, 20)
                    .build()));
        }
        int rw = width - (right - left);
        actions.add(host.add(Button.builder(Component.translatable("friendai.menu.skills.edit"), b -> action("edit"))
                .bounds(right, top + 27, rw, 20)
                .build()));
        int bottom = top + height;
        host.add(Button.builder(
                        Component.translatable("friendai.menu.skills.create"),
                        b -> Minecraft.getInstance()
                                .setScreenAndShow(SkillEditorScreen.create(
                                        Minecraft.getInstance().gui.screen(), state)))
                .bounds(left, bottom - 47, listWidth, 20)
                .build());
        host.add(Button.builder(
                        Component.translatable("friendai.menu.skills.import"),
                        b -> Minecraft.getInstance()
                                .setScreenAndShow(SkillEditorScreen.transfer(
                                        Minecraft.getInstance().gui.screen(), "", state)))
                .bounds(right, bottom - 47, rw, 20)
                .build());
        host.add(Button.builder(Component.translatable("friendai.menu.skills.previous"), b -> {
                    offset = 0;
                    queryPage(Math.max(0, model.view().map(SkillView::page).orElse(0) - 1));
                })
                .bounds(left, bottom - 23, Math.max(40, (listWidth - 4) / 2), 20)
                .build());
        host.add(Button.builder(Component.translatable("friendai.menu.skills.next"), b -> {
                    offset = 0;
                    queryPage(model.view().map(SkillView::page).orElse(0) + 1);
                })
                .bounds(left + (listWidth + 4) / 2, bottom - 23, Math.max(40, (listWidth - 4) / 2), 20)
                .build());
        open();
        tick();
    }

    private List<SkillView.Row> filtered() {
        String source = switch (sourceFilter) {
            case 1 -> "OWNER";
            case 2 -> "MODEL";
            case 3 -> "BUILTIN";
            default -> "";
        };
        String scope = switch (scopeFilter) {
            case 1 -> "COMPANION";
            case 2 -> "SERVER";
            default -> "";
        };
        return model.view()
                .map(v -> v.rows().stream()
                        .filter(r -> source.isEmpty() || r.source().equals(source))
                        .filter(r -> scope.isEmpty() || r.scope().equals(scope))
                        .filter(r -> stateFilter == 0
                                || (stateFilter == 1 ? r.activation().equals("PENDING") : r.enabled()))
                        .toList())
                .orElse(List.of());
    }

    private void action(String operation) {
        var detail = model.view().map(SkillView::detail).orElse(null);
        if (detail == null || !detail.id().equals(selected)) return;
        if (operation.equals("edit")) {
            Minecraft.getInstance()
                    .setScreenAndShow(
                            SkillEditorScreen.edit(Minecraft.getInstance().gui.screen(), detail, state));
            return;
        }
        if (operation.equals("delete") || operation.equals("reject")) {
            if (!deleteArmed.equals(operation + selected)) {
                deleteArmed = operation + selected;
                return;
            }
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", detail.id());
        o.addProperty("expectedVersion", detail.version());
        CompanionRequests.skills(operation, o.toString());
        deleteArmed = "";
    }

    @Override
    public void tick() {
        state.skillsView().ifPresent(model::onView);
        sourceButton.setMessage(Component.translatable(
                "friendai.menu.skills.filter_source_value",
                SkillLabels.value(
                        switch (sourceFilter) {
                            case 1 -> "OWNER";
                            case 2 -> "MODEL";
                            case 3 -> "BUILTIN";
                            default -> "ALL";
                        })));
        scopeButton.setMessage(Component.translatable(
                "friendai.menu.skills.filter_scope_value",
                SkillLabels.value(
                        switch (scopeFilter) {
                            case 1 -> "COMPANION";
                            case 2 -> "SERVER";
                            default -> "ALL";
                        })));
        stateButton.setMessage(Component.translatable(
                "friendai.menu.skills.filter_state_value",
                SkillLabels.value(
                        switch (stateFilter) {
                            case 1 -> "PENDING";
                            case 2 -> "ON";
                            default -> "ALL";
                        })));
        var list = filtered();
        offset = Math.clamp(offset, 0, Math.max(0, list.size() - rows.size()));
        for (int i = 0; i < rows.size(); i++) {
            Button b = rows.get(i);
            int index = offset + i;
            b.visible = index < list.size();
            if (!b.visible) continue;
            var row = list.get(index);
            b.setMessage(Component.translatable(
                    "friendai.menu.skills.row",
                    title(row),
                    Component.translatable(row.enabled() ? "friendai.menu.skills.on" : "friendai.menu.skills.off")));
            b.setTooltip(Tooltip.create(Component.literal(row.name() + ": " + row.description())));
        }
        boolean detail = model.view()
                .map(v -> v.detail() != null && v.detail().id().equals(selected))
                .orElse(false);
        for (Button b : actions) b.active = detail;
        model.view()
                .filter(v -> !v.exportText().isEmpty() && !v.exportText().equals(lastExport))
                .ifPresent(v -> {
                    lastExport = v.exportText();
                    Minecraft.getInstance()
                            .setScreenAndShow(SkillEditorScreen.transfer(
                                    Minecraft.getInstance().gui.screen(), lastExport, state));
                });
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        offset += amount < 0 ? 1 : -1;
        tick();
        return true;
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        var view = model.view();
        if (view.isEmpty()) return;
        int y = top + 55;
        var detail = view.get().detail();
        if (detail != null && detail.id().equals(selected)) {
            for (var line : font.split(Component.literal(detail.description()), Math.max(40, width - (right - left)))) {
                if (y > top + height - 72) break;
                Ui.text(g, font, line, right, y, Ui.TEXT);
                y += font.lineHeight + 1;
            }
            if (y < top + height - 72)
                Ui.text(
                        g,
                        font,
                        Component.translatable(
                                "friendai.menu.skills.metadata",
                                SkillLabels.value(detail.source().name()),
                                SkillLabels.value(detail.activation().name()),
                                detail.version()),
                        right,
                        y,
                        Ui.DIM);
        }
        if (!view.get().statusKey().isEmpty())
            Ui.text(
                    g,
                    font,
                    Component.translatable(view.get().statusKey()),
                    right,
                    top + height - 20,
                    view.get().ok() ? Ui.GOOD : Ui.BAD);
    }
}
