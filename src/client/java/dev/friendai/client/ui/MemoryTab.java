package dev.friendai.client.ui;

import com.google.gson.JsonObject;
import dev.friendai.core.Components;
import dev.friendai.debug.MemoryTabModel;
import dev.friendai.debug.RequestType;
import dev.friendai.memory.Category;
import dev.friendai.memory.MemoryView;
import dev.friendai.memory.MenuStatus;
import dev.friendai.memory.PersonaPresets;
import dev.friendai.memory.PersonaText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/** The memory tab: who the companion remembers on the left, their facts as cards on the right, with editing. */
final class MemoryTab implements MenuTab {
    private static final int ROW = 20;
    private static final int GAP = 4;
    private static final int LINE = 10;
    private static final int GROUP_ROW = 22;
    private static final int PAD = 8;
    private static final int PERSONA_PRESETS_Y = 4;

    private final MemoryTabModel model = new MemoryTabModel();
    private final ScrollView groupsView = new ScrollView(false);
    private final ScrollView factsView = new ScrollView(false);
    private MemoryView lastView;

    private FlatButton trust;
    private FlatButton clear;
    private FlatButton edit;
    private FlatButton forget;
    private FlatButton categoryButton;
    private EditBox text;
    private MultiLineEditBox personaBox;
    private HitArea factsHit;
    private FlatButton addOrSave;
    private FlatButton cancel;
    private Category draftCategory = Category.PROFILE;

    private List<Row> rows = List.of();
    private List<MemoryView.FactRow> builtFor = List.of();
    private int builtWidth = -1;
    private int sideLeft;
    private int sideTop;
    private int sideWidth;
    private int sideHeight;
    private int listLeft;
    private int listTop;
    private int listWidth;
    private int listHeight;
    private int headLeft;
    private int headWidth;
    private int trustX;
    private int clearX;
    private boolean wasEditing;
    private boolean wasPersona;
    private final List<FlatButton> presetButtons = new ArrayList<>();

    private record Row(int factId, MemoryView.FactRow fact, List<FormattedCharSequence> lines, int height) {}

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        sideWidth = Math.max(70, Math.min(118, width / 4));
        sideLeft = left;
        sideTop = top;
        sideHeight = height;
        headLeft = left + sideWidth + GAP * 2;
        headWidth = width - sideWidth - GAP * 2;

        int clearWidth = 56;
        int trustWidth = 76;
        clearX = headLeft + headWidth - clearWidth;
        trustX = clearX - GAP - trustWidth;
        clear = host.add(new FlatButton(
                clearX,
                top,
                clearWidth,
                ROW,
                Component.translatable("friendai.menu.memory.clear"),
                Ui.BAD,
                this::clickClear));
        trust = host.add(new FlatButton(
                trustX,
                top,
                trustWidth,
                ROW,
                Component.translatable("friendai.menu.memory.trust_on"),
                Ui.GOOD,
                this::clickTrust));

        int statusY = top + height - LINE;
        int formY = statusY - GAP - ROW;
        int actionY = formY - GAP - ROW;
        listLeft = headLeft;
        listTop = top + ROW + GAP;
        listWidth = headWidth;
        listHeight = Math.max(LINE, actionY - GAP - listTop);

        host.add(new HitArea(sideLeft, sideTop, sideWidth, sideHeight, this::clickGroup));
        factsHit = host.add(new HitArea(listLeft, listTop, listWidth, listHeight, this::clickFact));
        edit = host.add(new FlatButton(
                headLeft,
                actionY,
                70,
                ROW,
                Component.translatable("friendai.menu.memory.edit"),
                Ui.ACCENT,
                this::startEdit));
        forget = host.add(new FlatButton(
                headLeft + 70 + GAP,
                actionY,
                70,
                ROW,
                Component.translatable("friendai.menu.memory.forget"),
                Ui.WARN,
                this::clickForget));

        // At the smallest GUI the rail leaves a narrow content column: shrink the form instead of overlapping it.
        boolean narrow = headWidth < 260;
        int formGap = narrow ? 4 : GAP;
        int cancelWidth = narrow ? 48 : 58;
        int addWidth = narrow ? 52 : 64;
        int cancelX = headLeft + headWidth - cancelWidth;
        int addX = cancelX - formGap - addWidth;
        int categoryWidth = narrow ? 68 : 92;
        int textX = headLeft + categoryWidth + formGap;
        int textWidth = Math.max(narrow ? 30 : 40, addX - formGap - textX);
        draftCategory = model.draftCategory();
        categoryButton = host.add(new FlatButton(
                headLeft,
                formY,
                categoryWidth,
                ROW,
                categoryLabel(draftCategory),
                categoryColor(draftCategory),
                this::cycleCategory));
        text = host.add(new EditBox(
                host.font(), textX, formY, textWidth, ROW, Component.translatable("friendai.menu.memory.text_hint")));
        text.setHint(Component.translatable("friendai.menu.memory.text_hint"));
        text.setMaxLength(160);
        text.setValue(model.draftText());
        text.setResponder(s -> {
            if (!model.personaSelected()) {
                model.setDraft(draftCategory, s);
            }
        });
        addOrSave = host.add(new FlatButton(
                addX, formY, addWidth, ROW, Component.translatable("friendai.menu.memory.add"), Ui.GOOD, this::submit));
        cancel = host.add(new FlatButton(
                cancelX, formY, cancelWidth, ROW, Component.translatable("friendai.menu.memory.cancel"), Ui.DIM, () -> {
                    if (model.personaSelected()) {
                        net.minecraft.client.Minecraft.getInstance()
                                .setScreenAndShow(
                                        SkillEditorScreen.description(net.minecraft.client.Minecraft.getInstance()
                                                .gui
                                                .screen()));
                        return;
                    }
                    model.cancelEdit();
                    text.setValue("");
                    sync();
                }));

        presetButtons.clear();
        int presetWidth = (headWidth - GAP * 2) / 3;
        List<PersonaPresets.Preset> presets = PersonaPresets.all();
        for (int i = 0; i < presets.size(); i++) {
            PersonaPresets.Preset preset = presets.get(i);
            int px = headLeft + (i % 3) * (presetWidth + GAP);
            int py = listTop + PERSONA_PRESETS_Y + (i / 3) * (ROW + GAP);
            presetButtons.add(host.add(new FlatButton(
                    px,
                    py,
                    presetWidth,
                    ROW,
                    Component.translatable(preset.labelKey()),
                    Ui.ACCENT,
                    () -> personaBox.setValue(preset.text()))));
        }
        int boxTop = listTop + PERSONA_PRESETS_Y + 2 * (ROW + GAP) + LINE + GAP;
        personaBox = host.add(MultiLineEditBox.builder()
                .setX(headLeft)
                .setY(boxTop)
                .setPlaceholder(Component.translatable("friendai.menu.description.hint"))
                .build(
                        host.font(),
                        headWidth,
                        Math.max(30, formY - GAP - boxTop),
                        Component.translatable("friendai.menu.description.title")));
        personaBox.setCharacterLimit(PersonaText.MAX_LENGTH);
        wasPersona = false;
        sync();
    }

    private void cycleCategory() {
        Category[] all = Category.values();
        setCategory(all[(draftCategory.ordinal() + 1) % all.length]);
        model.setDraft(draftCategory, text.getValue());
    }

    private void setCategory(Category category) {
        draftCategory = category;
        categoryButton.setMessage(categoryLabel(category));
        categoryButton.setStatus(categoryColor(category));
    }

    @Override
    public void tick() {
        CompanionClientState.memoryView().ifPresent(view -> {
            if (view != lastView) {
                lastView = view;
                model.onView(view);
                if (model.personaSelected()) {
                    if (view.status().isPresent() && !personaBox.isFocused()) {
                        personaBox.setValue(view.persona().text());
                    }
                } else {
                    text.setMaxLength(view.limits().maxFactChars());
                }
            }
        });
        sync();
    }

    private void openPersona() {
        model.selectPersona();
        sync();
        personaBox.setValue(model.view().map(v -> v.persona().text()).orElse(""));
        wasPersona = true;
        sync();
    }

    private void leavePersona() {
        wasPersona = false;
        text.setMaxLength(model.view().map(v -> v.limits().maxFactChars()).orElse(160));
        text.setValue("");
    }

    private void sync() {
        boolean enabled = model.view().map(MemoryView::enabled).orElse(true);
        boolean persona = model.personaSelected();
        if (wasPersona && !persona) {
            leavePersona();
        }
        Optional<MemoryView.Group> group = model.group();
        boolean nowEditing = model.editing().isPresent();
        if (wasEditing && !nowEditing && !persona) {
            text.setValue("");
            setCategory(Category.PROFILE);
        }
        wasEditing = nowEditing;
        for (FlatButton preset : presetButtons) {
            preset.visible = persona;
        }
        personaBox.visible = persona;
        factsHit.active = !persona;
        if (persona) {
            clear.active = false;
            trust.visible = false;
            edit.visible = false;
            forget.visible = false;
            categoryButton.visible = false;
            text.visible = false;
            addOrSave.visible = true;
            addOrSave.active = true;
            addOrSave.setMessage(Component.translatable("friendai.menu.description.save"));
            cancel.visible = true;
            cancel.setMessage(Component.translatable("friendai.menu.description.details_button"));
            return;
        }
        cancel.setMessage(Component.translatable("friendai.menu.memory.cancel"));

        clear.active = group.isPresent() && enabled;
        clear.setMessage(Component.translatable(
                model.clearArmed(System.currentTimeMillis())
                        ? "friendai.menu.memory.clear_confirm"
                        : "friendai.menu.memory.clear"));
        if (group.isPresent()) {
            MemoryView.Group current = group.get();
            boolean playerGroup = current.role() == MemoryView.Role.TRUSTED || current.role() == MemoryView.Role.GUEST;
            trust.visible = playerGroup;
            boolean trusted = current.role() == MemoryView.Role.TRUSTED;
            trust.setMessage(Component.translatable(
                    trusted ? "friendai.menu.memory.trust_on" : "friendai.menu.memory.trust_off"));
            trust.setStatus(trusted ? Ui.GOOD : Ui.DIM);
        } else {
            trust.visible = false;
        }
        edit.visible = enabled;
        forget.visible = enabled;
        categoryButton.visible = enabled;
        text.visible = enabled;
        addOrSave.visible = enabled;
        cancel.visible = enabled && nowEditing;
        edit.active = enabled && model.selected().isPresent() && !nowEditing;
        forget.active = enabled && model.selected().isPresent();
        addOrSave.active = enabled && group.isPresent();
        addOrSave.setMessage(
                Component.translatable(nowEditing ? "friendai.menu.memory.save" : "friendai.menu.memory.add"));
    }

    private void startEdit() {
        if (model.startEdit()) {
            setCategory(model.draftCategory());
            text.setValue(model.draftText());
            sync();
        }
    }

    private void submit() {
        if (model.personaSelected()) {
            CompanionRequests.send(RequestType.PERSONA_SET, personaBox.getValue(), "");
            return;
        }
        Optional<MemoryView.Group> group = model.group();
        if (group.isEmpty()) {
            return;
        }
        String body = body(draftCategory, text.getValue());
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

    private static Component categoryLabel(Category category) {
        return Component.translatable(
                switch (category) {
                    case PROFILE -> "friendai.menu.memory.category.profile";
                    case PREFERENCE -> "friendai.menu.memory.category.preference";
                    case PLACE -> "friendai.menu.memory.category.place";
                    case PROMISE -> "friendai.menu.memory.category.promise";
                    case NOTE -> "friendai.menu.memory.category.note";
                });
    }

    private static int categoryColor(Category category) {
        return Ui.PALETTE[category.ordinal() % Ui.PALETTE.length];
    }

    private static int roleColor(MemoryView.Role role) {
        return switch (role) {
            case OWNER -> Ui.GOOD;
            case TRUSTED -> Ui.ACCENT;
            case GUEST -> Ui.DIM;
            case WORLD -> Ui.PURPLE;
        };
    }

    private static String roleKey(MemoryView.Role role) {
        return switch (role) {
            case OWNER -> "friendai.menu.memory.role.owner";
            case TRUSTED -> "friendai.menu.memory.role.trusted";
            case GUEST -> "friendai.menu.memory.role.guest";
            case WORLD -> "friendai.menu.memory.world";
        };
    }

    private static Component groupName(MemoryView.Group group) {
        return switch (group.role()) {
            case OWNER -> Component.translatable("friendai.menu.memory.you", group.name());
            case WORLD -> Component.translatable("friendai.menu.memory.world");
            default -> Component.literal(group.name());
        };
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Optional<MemoryView> view = model.view();
        if (view.isEmpty()) {
            return;
        }
        drawSidebar(g, font, view.get(), mouseX, mouseY);
        if (model.personaSelected()) {
            drawPersona(g, font, view.get());
            drawStatus(g, font, top, height);
            return;
        }
        model.group().ifPresent(group -> drawHeader(g, font, view.get(), group));
        if (!view.get().enabled()) {
            Ui.text(
                    g,
                    font,
                    Component.translatable("friendai.menu.memory.error.disabled"),
                    listLeft,
                    listTop + 4,
                    Ui.BAD);
        } else {
            drawFacts(g, font);
        }
        drawStatus(g, font, top, height);
    }

    private void drawSidebar(GuiGraphicsExtractor g, Font font, MemoryView view, int mouseX, int mouseY) {
        Ui.card(g, sideLeft, sideTop, sideWidth, sideHeight, Ui.CARD, Ui.BORDER);
        List<MemoryView.Group> groups = view.groups();
        groupsView.begin(
                g, sideLeft + 1, sideTop + 1, sideWidth - 2, sideHeight - 2, (groups.size() + 1) * GROUP_ROW + 2);
        int y = groupsView.top() + 1;
        if (groupsView.visible(y, GROUP_ROW)) {
            boolean current = model.personaSelected();
            boolean hot = groupsView.contains(mouseX, mouseY) && mouseY >= y && mouseY < y + GROUP_ROW;
            if (current) {
                g.fill(sideLeft + 1, y, sideLeft + sideWidth - 1, y + GROUP_ROW, Ui.CARD_HOVER);
                g.fill(sideLeft + 1, y, sideLeft + 3, y + GROUP_ROW, Ui.ACCENT);
            } else if (hot) {
                g.fill(sideLeft + 1, y, sideLeft + sideWidth - 1, y + GROUP_ROW, Ui.CARD_HOVER);
            }
            Ui.dot(g, sideLeft + 8, y + GROUP_ROW / 2 - 2, 4, Ui.PURPLE);
            Ui.text(
                    g,
                    font,
                    Ui.clip(
                            font,
                            Component.translatable("friendai.menu.description.title")
                                    .getString(),
                            sideWidth - 16 - 8),
                    sideLeft + 16,
                    y + (GROUP_ROW - font.lineHeight) / 2 + 1,
                    current ? Ui.TEXT : Ui.DIM);
        }
        y += GROUP_ROW;
        String selected = model.group().map(MemoryView.Group::key).orElse("");
        for (MemoryView.Group group : groups) {
            if (groupsView.visible(y, GROUP_ROW)) {
                boolean current = group.key().equals(selected);
                boolean hot = groupsView.contains(mouseX, mouseY) && mouseY >= y && mouseY < y + GROUP_ROW;
                if (current) {
                    g.fill(sideLeft + 1, y, sideLeft + sideWidth - 1, y + GROUP_ROW, Ui.CARD_HOVER);
                    g.fill(sideLeft + 1, y, sideLeft + 3, y + GROUP_ROW, Ui.ACCENT);
                } else if (hot) {
                    g.fill(sideLeft + 1, y, sideLeft + sideWidth - 1, y + GROUP_ROW, Ui.CARD_HOVER);
                }
                Ui.dot(g, sideLeft + 8, y + GROUP_ROW / 2 - 2, 4, roleColor(group.role()));
                String count = String.valueOf(group.count());
                int nameWidth = sideWidth - 8 - 8 - 6 - font.width(count) - 8;
                Ui.text(
                        g,
                        font,
                        Ui.clip(font, groupName(group).getString(), nameWidth),
                        sideLeft + 16,
                        y + (GROUP_ROW - font.lineHeight) / 2 + 1,
                        current ? Ui.TEXT : Ui.DIM);
                Ui.text(
                        g,
                        font,
                        count,
                        sideLeft + sideWidth - 8 - font.width(count),
                        y + (GROUP_ROW - font.lineHeight) / 2 + 1,
                        Ui.DIM);
                if (group.online() && group.role() != MemoryView.Role.WORLD) {
                    Ui.dot(g, sideLeft + sideWidth - 6, y + 2, 3, Ui.GOOD);
                }
            }
            y += GROUP_ROW;
        }
        groupsView.end(g);
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, MemoryView view, MemoryView.Group group) {
        int limit = group.role() == MemoryView.Role.WORLD
                ? view.limits().maxWorldFacts()
                : view.limits().maxFactsPerPlayer();
        int right = (trust.visible ? trustX : clearX) - GAP;
        int y = sideTop + (ROW - font.lineHeight) / 2;
        String name = groupName(group).getString();
        String counter = group.count() + "/" + limit;
        Component role = Component.translatable(roleKey(group.role()));
        int badgeWidth = group.role() == MemoryView.Role.WORLD ? 0 : Ui.badgeWidth(font, role.getString());
        int counterWidth = font.width(counter);
        int nameWidth = Math.max(20, right - headLeft - badgeWidth - counterWidth - 12);
        String shown = Ui.clip(font, name, nameWidth);
        Ui.text(g, font, shown, headLeft, y, Ui.TEXT);
        int x = headLeft + font.width(shown) + 6;
        if (badgeWidth > 0 && x + badgeWidth <= right) {
            Ui.badge(g, font, role.getString(), x, sideTop + 4, roleColor(group.role()));
            x += badgeWidth + 6;
        }
        if (x + counterWidth <= right) {
            boolean full = group.count() >= limit;
            Ui.text(g, font, counter, x, y, full ? Ui.WARN : Ui.DIM);
        }
    }

    private void drawPersona(GuiGraphicsExtractor g, Font font, MemoryView view) {
        int y = sideTop + (ROW - font.lineHeight) / 2;
        Component title = Component.translatable("friendai.menu.description.title");
        Ui.text(g, font, title, headLeft, y, Ui.TEXT);
        if (view.persona().custom()) {
            Component custom = Component.translatable("friendai.menu.description.custom");
            Ui.badge(g, font, custom.getString(), headLeft + font.width(title) + 6, sideTop + 4, Ui.PURPLE);
        }
        String current = personaBox.getValue().strip();
        for (int i = 0; i < presetButtons.size(); i++) {
            presetButtons.get(i).setStatus(PersonaPresets.all().get(i).text().equals(current) ? Ui.GOOD : Ui.ACCENT);
        }
        String counter = personaBox.getValue().length() + "/" + PersonaText.MAX_LENGTH;
        int counterY = listTop + PERSONA_PRESETS_Y + 2 * (ROW + GAP) + 2;
        Ui.text(g, font, counter, headLeft + headWidth - font.width(counter), counterY, Ui.DIM);
    }

    private void rebuildRows(Font font, List<MemoryView.FactRow> facts, int width) {
        builtFor = facts;
        builtWidth = width;
        List<Row> built = new ArrayList<>();
        for (MemoryView.FactRow fact : facts) {
            List<FormattedCharSequence> lines =
                    font.split(Component.literal(fact.text()), Math.max(20, width - 2 * PAD - 8));
            built.add(new Row(fact.id(), fact, lines, 18 + lines.size() * (font.lineHeight + 1) + 4));
        }
        rows = built;
    }

    private void drawFacts(GuiGraphicsExtractor g, Font font) {
        List<MemoryView.FactRow> facts = model.facts();
        if (!facts.equals(builtFor) || builtWidth != listWidth) {
            rebuildRows(font, facts, listWidth);
        }
        if (facts.isEmpty()) {
            Ui.text(
                    g,
                    font,
                    Component.translatable("friendai.menu.memory.empty_group"),
                    listLeft + 2,
                    listTop + 4,
                    Ui.DIM);
            return;
        }
        int total = 0;
        for (Row row : rows) {
            total += row.height() + GAP;
        }
        int selected = model.selected().orElse(-1);
        factsView.begin(g, listLeft, listTop, listWidth, listHeight, total);
        int y = factsView.top();
        int cardWidth = listWidth - 6;
        for (Row row : rows) {
            if (factsView.visible(y, row.height())) {
                boolean current = row.factId() == selected;
                Ui.card(
                        g,
                        listLeft,
                        y,
                        cardWidth,
                        row.height(),
                        current ? Ui.CARD_HOVER : Ui.CARD,
                        current ? Ui.ACCENT : Ui.BORDER);
                int color = categoryColor(row.fact().category());
                g.fill(listLeft, y, listLeft + 3, y + row.height(), color);
                int x = listLeft + PAD;
                x += Ui.badge(g, font, categoryLabel(row.fact().category()).getString(), x, y + 3, color) + 5;
                Ui.text(g, font, "#" + row.factId(), x, y + 5, Ui.DIM);
                if (!row.fact().authorName().isEmpty()) {
                    String from = Component.translatable(
                                    "friendai.menu.memory.from", row.fact().authorName())
                            .getString();
                    from = Ui.clip(font, from, cardWidth / 2);
                    Ui.text(g, font, from, listLeft + cardWidth - PAD - font.width(from), y + 5, Ui.DIM);
                }
                int lineY = y + 18;
                for (FormattedCharSequence line : row.lines()) {
                    Ui.text(g, font, line, listLeft + PAD, lineY, Ui.TEXT);
                    lineY += font.lineHeight + 1;
                }
            }
            y += row.height() + GAP;
        }
        factsView.end(g);
    }

    private void drawStatus(GuiGraphicsExtractor g, Font font, int top, int height) {
        Optional<MenuStatus> status = model.status();
        if (status.isEmpty()) {
            return;
        }
        Component message = Components.of(status.get().message());
        int color = status.get().ok() ? Ui.GOOD : Ui.BAD;
        int y = top + height - LINE;
        g.enableScissor(headLeft, y, headLeft + headWidth, y + LINE + 2);
        Ui.text(g, font, message, headLeft, y + 1, color);
        g.disableScissor();
    }

    private void clickGroup(double x, double y) {
        int index = groupsView.contentY(y) / GROUP_ROW - 1;
        if (index == -1 && groupsView.contentY(y) >= 0) {
            openPersona();
            return;
        }
        model.view().ifPresent(view -> {
            if (index >= 0 && index < view.groups().size()) {
                model.selectGroup(view.groups().get(index).key());
                factsView.scroll(1000);
                sync();
            }
        });
    }

    private void clickFact(double x, double y) {
        int cursor = factsView.contentY(y);
        int running = 0;
        for (Row row : rows) {
            if (cursor >= running && cursor < running + row.height()) {
                model.select(row.factId());
                sync();
                return;
            }
            running += row.height() + GAP;
        }
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        if (groupsView.contains(x, y)) {
            return groupsView.scroll(amount);
        }
        return factsView.contains(x, y) && factsView.scroll(amount);
    }
}
