package dev.friendai.client.ui;

import com.google.gson.JsonObject;
import dev.friendai.core.Components;
import dev.friendai.debug.MemoryTabModel;
import dev.friendai.debug.RequestType;
import dev.friendai.memory.Category;
import dev.friendai.memory.MemoryView;
import dev.friendai.memory.MenuStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The memory tab: browse facts by group, add, edit and forget them, and grant or revoke trust. */
final class MemoryTab implements MenuTab {
    private static final int ROW = 20;
    private static final int GAP = 4;
    private static final int LINE = 10;
    private static final int BUTTON = 70;
    private static final int SMALL = 60;

    private final MemoryTabModel model = new MemoryTabModel();
    private MemoryView lastView;

    private Button previous;
    private Button next;
    private Button trust;
    private Button clear;
    private Button edit;
    private Button forget;
    private CycleButton<Category> category;
    private EditBox text;
    private Button addOrSave;
    private Button cancel;

    private List<Row> rows = List.of();
    private List<MemoryView.FactRow> builtFor = List.of();
    private int builtWidth = -1;
    private int scrollPixels;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;
    private boolean wasEditing;
    private int clearX;
    private int trustX;

    private record Row(int factId, List<FormattedCharSequence> lines, int height) {}

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        previous = host.add(Button.builder(Component.literal("<"), b -> {
                    model.previous();
                    sync();
                })
                .bounds(left, top, ROW, ROW)
                .build());
        next = host.add(Button.builder(Component.literal(">"), b -> {
                    model.next();
                    sync();
                })
                .bounds(left + width - ROW, top, ROW, ROW)
                .build());
        clearX = left + width - ROW - GAP - SMALL;
        clear = host.add(Button.builder(Component.translatable("friendai.menu.memory.clear"), b -> clickClear())
                .bounds(clearX, top, SMALL, ROW)
                .build());
        trustX = clearX - GAP - BUTTON;
        trust = host.add(Button.builder(Component.translatable("friendai.menu.memory.trust_on"), b -> clickTrust())
                .bounds(trustX, top, BUTTON, ROW)
                .build());

        int statusY = top + height - LINE;
        int formY = statusY - GAP - ROW;
        int actionY = formY - GAP - ROW;
        listLeft = left;
        listTop = top + ROW + GAP;
        listWidth = width;
        listHeight = Math.max(LINE, actionY - GAP - listTop);

        edit = host.add(Button.builder(Component.translatable("friendai.menu.memory.edit"), b -> startEdit())
                .bounds(left, actionY, BUTTON, ROW)
                .build());
        forget = host.add(Button.builder(Component.translatable("friendai.menu.memory.forget"), b -> clickForget())
                .bounds(left + BUTTON + GAP, actionY, BUTTON, ROW)
                .build());

        int cancelX = left + width - SMALL;
        int addX = cancelX - GAP - BUTTON;
        int categoryWidth = 110;
        int textX = left + categoryWidth + GAP;
        int textWidth = Math.max(40, addX - GAP - textX);
        category = host.add(CycleButton.<Category>builder(c -> Component.literal(c.name()), model.draftCategory())
                .withValues(List.of(Category.values()))
                .create(left, formY, categoryWidth, ROW, Component.empty(), (button, value) ->
                        model.setDraft(value, text == null ? model.draftText() : text.getValue())));
        text = host.add(new EditBox(
                host.font(), textX, formY, textWidth, ROW, Component.translatable("friendai.menu.memory.text_hint")));
        text.setHint(Component.translatable("friendai.menu.memory.text_hint"));
        text.setMaxLength(160);
        text.setValue(model.draftText());
        text.setResponder(s -> model.setDraft(category.getValue(), s));
        addOrSave = host.add(Button.builder(Component.translatable("friendai.menu.memory.add"), b -> submit())
                .bounds(addX, formY, BUTTON, ROW)
                .build());
        cancel = host.add(Button.builder(Component.translatable("friendai.menu.memory.cancel"), b -> {
                    model.cancelEdit();
                    text.setValue("");
                    sync();
                })
                .bounds(cancelX, formY, SMALL, ROW)
                .build());
        sync();
    }

    @Override
    public void tick() {
        CompanionClientState.memoryView().ifPresent(view -> {
            if (view != lastView) {
                lastView = view;
                model.onView(view);
                text.setMaxLength(view.limits().maxFactChars());
            }
        });
        sync();
    }

    private void sync() {
        boolean enabled = model.view().map(MemoryView::enabled).orElse(true);
        Optional<MemoryView.Group> group = model.group();
        boolean nowEditing = model.editing().isPresent();
        if (wasEditing && !nowEditing) {
            text.setValue("");
            category.setValue(Category.PROFILE);
        }
        wasEditing = nowEditing;

        previous.active = group.isPresent();
        next.active = group.isPresent();
        clear.active = group.isPresent() && enabled;
        clear.setMessage(Component.translatable(model.clearArmed(System.currentTimeMillis())
                ? "friendai.menu.memory.clear_confirm"
                : "friendai.menu.memory.clear"));
        if (group.isPresent()) {
            MemoryView.Group current = group.get();
            boolean playerGroup = current.role() == MemoryView.Role.TRUSTED || current.role() == MemoryView.Role.GUEST;
            trust.visible = playerGroup;
            trust.setMessage(Component.translatable(current.role() == MemoryView.Role.TRUSTED
                    ? "friendai.menu.memory.trust_on"
                    : "friendai.menu.memory.trust_off"));
        } else {
            trust.visible = false;
        }
        edit.visible = enabled;
        forget.visible = enabled;
        category.visible = enabled;
        text.visible = enabled;
        addOrSave.visible = enabled;
        cancel.visible = enabled && nowEditing;
        edit.active = enabled && model.selected().isPresent() && !nowEditing;
        forget.active = enabled && model.selected().isPresent();
        addOrSave.active = enabled && group.isPresent();
        addOrSave.setMessage(Component.translatable(
                nowEditing ? "friendai.menu.memory.save" : "friendai.menu.memory.add"));
    }

    private void startEdit() {
        if (model.startEdit()) {
            category.setValue(model.draftCategory());
            text.setValue(model.draftText());
            sync();
        }
    }

    private void submit() {
        Optional<MemoryView.Group> group = model.group();
        if (group.isEmpty()) {
            return;
        }
        String body = body(category.getValue(), text.getValue());
        OptionalInt editing = model.editing();
        if (editing.isPresent()) {
            CompanionRequests.send(RequestType.MEMORY_EDIT, String.valueOf(editing.getAsInt()), body);
            model.cancelEdit();
            text.setValue("");
        } else {
            CompanionRequests.send(RequestType.MEMORY_ADD, group.get().key(), body);
        }
        sync();
    }

    private void clickForget() {
        model.selected().ifPresent(id -> CompanionRequests.send(RequestType.MEMORY_FORGET, String.valueOf(id), ""));
    }

    private void clickClear() {
        model.group().ifPresent(group -> {
            if (model.clickClear(System.currentTimeMillis())) {
                CompanionRequests.send(RequestType.MEMORY_CLEAR, group.key(), "");
            }
            sync();
        });
    }

    private void clickTrust() {
        model.group().ifPresent(group -> {
            boolean trusted = group.role() == MemoryView.Role.TRUSTED;
            CompanionRequests.send(RequestType.MEMORY_TRUST, group.key(), trusted ? "false" : "true");
        });
    }

    private static String body(Category category, String text) {
        JsonObject json = new JsonObject();
        json.addProperty("category", category.name());
        json.addProperty("text", text);
        return json.toString();
    }

    @Override
    public void render(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        if (model.view().isEmpty()) {
            return;
        }
        model.group().ifPresent(group -> drawGroupLabel(g, font, group, top));
        if (!model.view().orElseThrow().enabled()) {
            g.text(font, Component.translatable("friendai.menu.memory.error.disabled"), left, listTop, 0xFFFF5555);
            drawStatus(g, font, left, top, width, height);
            return;
        }
        drawFacts(g, font, width);
        drawStatus(g, font, left, top, width, height);
    }

    private void drawGroupLabel(GuiGraphicsExtractor g, Font font, MemoryView.Group group, int top) {
        MemoryView view = model.view().orElseThrow();
        int right = (trust.visible ? trustX : clearX) - GAP;
        int labelX = listLeft + ROW + GAP;
        Component label = groupLabel(view, group);
        g.enableScissor(labelX, top, Math.max(labelX, right), top + ROW);
        g.text(font, label, labelX, top + (ROW - font.lineHeight) / 2, 0xFFFFFFFF);
        g.disableScissor();
    }

    private static Component groupLabel(MemoryView view, MemoryView.Group group) {
        int limit = group.role() == MemoryView.Role.WORLD
                ? view.limits().maxWorldFacts()
                : view.limits().maxFactsPerPlayer();
        Component name;
        if (group.role() == MemoryView.Role.OWNER) {
            name = Component.translatable("friendai.menu.memory.you", group.name());
        } else if (group.role() == MemoryView.Role.WORLD) {
            name = Component.translatable("friendai.menu.memory.world");
        } else if (group.online()) {
            name = Component.literal(group.name() + " (")
                    .append(Component.translatable("friendai.menu.memory.online"))
                    .append(")");
        } else {
            name = Component.literal(group.name());
        }
        if (group.role() == MemoryView.Role.WORLD) {
            return Component.translatable(
                    "friendai.menu.memory.group_world", name, group.count(), limit);
        }
        return Component.translatable(
                "friendai.menu.memory.group",
                name,
                Component.translatable(roleKey(group.role())),
                group.count(),
                limit);
    }

    private static String roleKey(MemoryView.Role role) {
        return switch (role) {
            case OWNER -> "friendai.menu.memory.role.owner";
            case TRUSTED -> "friendai.menu.memory.role.trusted";
            case GUEST -> "friendai.menu.memory.role.guest";
            case WORLD -> "friendai.menu.memory.world";
        };
    }

    private void drawFacts(GuiGraphicsExtractor g, Font font, int width) {
        List<MemoryView.FactRow> facts = model.facts();
        if (!facts.equals(builtFor) || builtWidth != width) {
            builtFor = facts;
            builtWidth = width;
            List<Row> built = new ArrayList<>();
            for (MemoryView.FactRow fact : facts) {
                StringBuilder line = new StringBuilder()
                        .append('[')
                        .append(fact.id())
                        .append("] ")
                        .append(fact.category().name())
                        .append(": ")
                        .append(fact.text());
                if (!fact.authorName().isEmpty()) {
                    line.append(" (from ").append(fact.authorName()).append(')');
                }
                List<FormattedCharSequence> wrapped =
                        font.split(Component.literal(line.toString()), Math.max(1, width - 6));
                built.add(new Row(fact.id(), wrapped, wrapped.size() * LINE + 2));
            }
            rows = built;
            scrollPixels = 0;
        }
        if (facts.isEmpty()) {
            g.text(font, Component.translatable("friendai.menu.memory.empty_group"), listLeft, listTop, 0xFFAAAAAA);
            return;
        }
        int total = 0;
        for (Row row : rows) {
            total += row.height;
        }
        scrollPixels = Math.max(0, Math.min(scrollPixels, Math.max(0, total - listHeight)));
        int selected = model.selected().orElse(-1);
        g.enableScissor(listLeft, listTop, listLeft + listWidth, listTop + listHeight);
        int y = listTop - scrollPixels;
        for (Row row : rows) {
            if (row.factId() == selected) {
                g.fill(listLeft, y, listLeft + listWidth, y + row.height, 0x40FFFFFF);
            }
            int color = row.factId() == selected ? 0xFFFFFF55 : 0xFFFFFFFF;
            int lineY = y + 1;
            for (FormattedCharSequence line : row.lines()) {
                g.text(font, line, listLeft + 2, lineY, color);
                lineY += LINE;
            }
            y += row.height;
        }
        g.disableScissor();
    }

    private void drawStatus(GuiGraphicsExtractor g, Font font, int left, int top, int width, int height) {
        Optional<MenuStatus> status = model.status();
        if (status.isEmpty()) {
            return;
        }
        Component message = Components.of(status.get().message());
        int color = status.get().ok() ? 0xFF55FF55 : 0xFFFF5555;
        int y = top + height - LINE;
        g.enableScissor(left, y, left + width, y + LINE + 2);
        g.text(font, message, left, y, color);
        g.disableScissor();
    }

    @Override
    public boolean mouseClicked(double x, double y) {
        if (x < listLeft || x >= listLeft + listWidth || y < listTop || y >= listTop + listHeight) {
            return false;
        }
        int cursor = (int) (y - listTop + scrollPixels);
        int running = 0;
        for (Row row : rows) {
            if (cursor >= running && cursor < running + row.height) {
                model.select(row.factId());
                sync();
                return true;
            }
            running += row.height;
        }
        return false;
    }

    @Override
    public boolean scroll(double amount) {
        scrollPixels -= (int) Math.round(amount * 12);
        return true;
    }
}
