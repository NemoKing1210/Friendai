package dev.friendai.client.ui;

import com.google.gson.*;
import dev.friendai.debug.RequestType;
import dev.friendai.skill.*;
import java.util.*;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Keyboard-accessible multiline editor; all saves remain server-validated. */
final class SkillEditorScreen extends Screen {
    private enum Mode {
        SKILL,
        DESCRIPTION,
        TRANSFER
    }

    private final Screen parent;
    private final Mode mode;
    private InstructionSkill current;
    private final String transfer;
    private final SkillEditorModel model = new SkillEditorModel();
    private EditBox name, description, kinds, triggers, tags, language, address;
    private MultiLineEditBox body;
    private String statusKey = "";
    private boolean deleteArmed, loaded, awaitingSave;
    private String awaitingAction = "";
    private int left, w, bodyY;

    private SkillEditorScreen(Screen parent, Mode mode, InstructionSkill current, String transfer) {
        super(Component.translatable(
                mode == Mode.DESCRIPTION
                        ? "friendai.menu.description.title"
                        : mode == Mode.TRANSFER ? "friendai.menu.skills.transfer" : "friendai.menu.skills.edit"));
        this.parent = parent;
        this.mode = mode;
        this.current = current;
        this.transfer = transfer;
    }

    static SkillEditorScreen create(Screen parent) {
        return new SkillEditorScreen(parent, Mode.SKILL, null, "");
    }

    static SkillEditorScreen edit(Screen parent, InstructionSkill skill) {
        return new SkillEditorScreen(parent, Mode.SKILL, skill, "");
    }

    static SkillEditorScreen description(Screen parent) {
        return new SkillEditorScreen(parent, Mode.DESCRIPTION, null, "");
    }

    static SkillEditorScreen transfer(Screen parent, String text) {
        return new SkillEditorScreen(parent, Mode.TRANSFER, null, text);
    }

    @Override
    public void resize(int width, int height) {
        String[] values = body == null
                ? null
                : mode == Mode.SKILL
                        ? new String[] {
                            name.getValue(),
                            description.getValue(),
                            kinds.getValue(),
                            triggers.getValue(),
                            tags.getValue(),
                            body.getValue()
                        }
                        : mode == Mode.DESCRIPTION
                                ? new String[] {
                                    name.getValue(), language.getValue(), address.getValue(), body.getValue()
                                }
                                : new String[] {body.getValue()};
        super.resize(width, height);
        if (values == null) return;
        if (mode == Mode.SKILL) {
            name.setValue(values[0]);
            description.setValue(values[1]);
            kinds.setValue(values[2]);
            triggers.setValue(values[3]);
            tags.setValue(values[4]);
            body.setValue(values[5]);
        } else if (mode == Mode.DESCRIPTION) {
            name.setValue(values[0]);
            language.setValue(values[1]);
            address.setValue(values[2]);
            body.setValue(values[3]);
        } else body.setValue(values[0]);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void added() {
        CompanionRequests.send(RequestType.SUBSCRIBE);
        CompanionRequests.skills("open", "{}");
    }

    @Override
    public void removed() {
        CompanionRequests.skills("close", "{}");
        CompanionRequests.send(RequestType.UNSUBSCRIBE);
    }

    @Override
    public void onClose() {
        minecraft.setScreenAndShow(parent);
    }

    private EditBox field(String key, int x, int y, int width, int max, String value) {
        EditBox box = addRenderableWidget(new EditBox(font, x, y, width, 20, Component.translatable(key)));
        box.setMaxLength(max);
        box.setValue(value);
        box.setHint(Component.translatable(key));
        return box;
    }

    private Button button(String key, int x, int y, int width, Runnable action) {
        return addRenderableWidget(Button.builder(Component.translatable(key), b -> action.run())
                .bounds(x, y, width, 20)
                .tooltip(Tooltip.create(Component.translatable(key)))
                .build());
    }

    @Override
    protected void init() {
        left = Math.max(12, (width - 560) / 2);
        w = width - left * 2;
        var draft = model.draft().orElse(current == null ? SkillDraft.simple("", "") : current.draft());
        if (mode == Mode.SKILL) {
            name = field("friendai.menu.skills.name", left, 34, w, 80, draft.name());
            description = field("friendai.menu.skills.trigger_description", left, 68, w, 200, draft.description());
            int column = (w - 8) / 3;
            String kindText = String.join(
                            ",",
                            draft.turnKinds().stream()
                                    .map(k -> k.name().toLowerCase(Locale.ROOT))
                                    .sorted()
                                    .toList())
                    + (draft.alwaysOn() ? ",always" : "")
                    + (draft.includeProfile() ? ",profile" : "");
            kinds = field("friendai.menu.skills.kinds", left, 102, column, 200, kindText);
            triggers = field(
                    "friendai.menu.skills.triggers",
                    left + column + 4,
                    102,
                    column,
                    500,
                    String.join(",", draft.triggers()));
            tags = field(
                    "friendai.menu.skills.tags",
                    left + 2 * (column + 4),
                    102,
                    column,
                    500,
                    String.join(",", draft.tags()));
            bodyY = 136;
            body = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(left)
                    .setY(bodyY)
                    .setPlaceholder(Component.translatable("friendai.menu.skills.body"))
                    .build(
                            font,
                            w,
                            Math.max(24, height - bodyY - 74),
                            Component.translatable("friendai.menu.skills.body")));
            body.setCharacterLimit(4000);
            body.setValue(draft.body());
            int bw = (w - 8) / 3;
            button("friendai.menu.skills.save", left, height - 46, bw, this::save);
            button("friendai.menu.skills.revert", left + bw + 4, height - 46, bw, () -> {
                model.revert();
                rebuildWidgets();
            });
            button("friendai.menu.skills.back", left + 2 * (bw + 4), height - 46, bw, this::onClose);
            if (current != null) {
                String[] keys = {
                    "friendai.menu.skills.duplicate",
                    "friendai.menu.skills.toggle",
                    "friendai.menu.skills.delete",
                    "friendai.menu.skills.approve",
                    "friendai.menu.skills.reject",
                    "friendai.menu.skills.export"
                };
                String[] ops = {"duplicate", "toggle", "delete", "approve", "reject", "export"};
                int sw = (w - 20) / 6;
                for (int i = 0; i < ops.length; i++) {
                    final String op = ops[i];
                    button(keys[i], left + i * (sw + 4), height - 23, sw, () -> action(op));
                }
            }
        } else if (mode == Mode.DESCRIPTION) {
            var v = CompanionClientState.skillsView();
            String summary = v.map(s -> s.description().summary()).orElse("");
            name = field("friendai.menu.description.summary", left, 34, w, 300, summary);
            language = field(
                    "friendai.menu.description.language",
                    left,
                    68,
                    (w - 4) / 2,
                    35,
                    v.map(s -> s.ownerProfile().language()).orElse("auto"));
            address = field(
                    "friendai.menu.description.address",
                    left + (w + 4) / 2,
                    68,
                    (w - 4) / 2,
                    80,
                    v.map(s -> s.ownerProfile().address()).orElse(""));
            bodyY = 102;
            body = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(left)
                    .setY(bodyY)
                    .setPlaceholder(Component.translatable("friendai.menu.description.details"))
                    .build(
                            font,
                            w,
                            Math.max(30, height - bodyY - 50),
                            Component.translatable("friendai.menu.description.details")));
            body.setCharacterLimit(4000);
            body.setValue(v.map(s -> s.description().details()).orElse(""));
            loaded = v.isPresent();
            button("friendai.menu.skills.save", left, height - 24, (w - 8) / 3, this::save);
            button("friendai.menu.skills.revert", left + (w + 4) / 3, height - 24, (w - 8) / 3, () -> {
                loaded = false;
                rebuildWidgets();
            });
            button("friendai.menu.skills.back", left + 2 * (w + 4) / 3, height - 24, (w - 8) / 3, this::onClose);
        } else {
            bodyY = 36;
            body = addRenderableWidget(MultiLineEditBox.builder()
                    .setX(left)
                    .setY(bodyY)
                    .setPlaceholder(Component.translatable("friendai.menu.skills.transfer_hint"))
                    .build(
                            font,
                            w,
                            Math.max(30, height - 90),
                            Component.translatable("friendai.menu.skills.transfer")));
            body.setCharacterLimit(32768);
            body.setValue(transfer);
            int bw = (w - 12) / 4;
            button("friendai.menu.skills.paste", left, height - 24, bw, () -> {
                String clip = minecraft.keyboardHandler.getClipboard();
                if (clip.length() <= 32768) body.setValue(clip);
                else statusKey = "friendai.menu.skills.invalid";
            });
            button(
                    "friendai.menu.skills.copy",
                    left + bw + 4,
                    height - 24,
                    bw,
                    () -> minecraft.keyboardHandler.setClipboard(body.getValue()));
            button("friendai.menu.skills.import", left + 2 * (bw + 4), height - 24, bw, () -> {
                model.awaitResponse(CompanionRequests.skills("import", body.getValue()));
                statusKey = "friendai.menu.skills.sent";
            });
            button("friendai.menu.skills.back", left + 3 * (bw + 4), height - 24, bw, this::onClose);
        }
    }

    private SkillDraft draft() {
        Set<InstructionSkill.Kind> set = EnumSet.noneOf(InstructionSkill.Kind.class);
        boolean always = false, profile = false;
        for (String kind : terms(kinds.getValue())) {
            if (kind.equalsIgnoreCase("always")) always = true;
            else if (kind.equalsIgnoreCase("profile")) profile = true;
            else set.add(InstructionSkill.Kind.valueOf(kind.toUpperCase(Locale.ROOT)));
        }
        return new SkillDraft(
                name.getValue(),
                description.getValue(),
                body.getValue(),
                current == null || current.enabled(),
                always,
                set,
                terms(triggers.getValue()),
                terms(tags.getValue()),
                profile);
    }

    private static List<String> terms(String value) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private void save() {
        try {
            JsonObject o;
            if (mode == Mode.DESCRIPTION) {
                o = new JsonObject();
                o.addProperty("summary", name.getValue());
                o.addProperty("details", body.getValue());
                o.addProperty("language", language.getValue());
                o.addProperty("address", address.getValue());
                model.awaitResponse(CompanionRequests.skills("description", o.toString()));
            } else {
                SkillDraft d = SkillText.validate(draft(), s -> s);
                model.edit(d);
                o = new Gson().toJsonTree(d).getAsJsonObject();
                if (current != null) {
                    o.addProperty("id", current.id());
                    o.addProperty("expectedVersion", current.version());
                }
                model.awaitResponse(CompanionRequests.skills(current == null ? "create" : "edit", o.toString()));
            }
            awaitingSave = true;
            statusKey = "friendai.menu.skills.sent";
        } catch (RuntimeException e) {
            statusKey = "friendai.menu.skills.invalid";
        }
    }

    private void action(String operation) {
        if ((operation.equals("delete") || operation.equals("reject")) && !deleteArmed) {
            deleteArmed = true;
            statusKey = "friendai.menu.skills.confirm_delete";
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("id", current.id());
        o.addProperty("expectedVersion", current.version());
        awaitingAction = operation;
        model.awaitResponse(CompanionRequests.skills(operation, o.toString()));
        deleteArmed = false;
    }

    @Override
    public void tick() {
        CompanionClientState.skillsView().ifPresent(v -> {
            model.onView(v);
            if (mode == Mode.DESCRIPTION && !loaded) {
                loaded = true;
                name.setValue(v.description().summary());
                body.setValue(v.description().details());
                language.setValue(v.ownerProfile().language());
                address.setValue(v.ownerProfile().address());
            }
        });
        CompanionClientState.skillsResponse().filter(model::acceptResponse).ifPresent(v -> {
            statusKey = v.statusKey();
            if (!v.ok()) {
                awaitingSave = false;
                awaitingAction = "";
                return;
            }
            if (awaitingAction.equals("delete") || awaitingAction.equals("reject")) {
                awaitingAction = "";
                onClose();
                return;
            }
            if (awaitingAction.equals("export")) {
                awaitingAction = "";
                minecraft.setScreenAndShow(transfer(parent, v.exportText()));
                return;
            }
            if (mode == Mode.SKILL && v.detail() != null && (awaitingSave || !awaitingAction.isEmpty())) {
                current = v.detail();
                awaitingSave = false;
                awaitingAction = "";
                rebuildWidgets();
            }
        });
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Ui.panel(g, left - 6, 4, w + 12, height - 8);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        Ui.text(g, font, title, left, 12, Ui.TEXT);
        if (mode != Mode.TRANSFER) {
            Ui.text(
                    g,
                    font,
                    Component.translatable(
                            mode == Mode.DESCRIPTION
                                    ? "friendai.menu.description.summary"
                                    : "friendai.menu.skills.name"),
                    left,
                    24,
                    Ui.DIM);
            Ui.text(
                    g,
                    font,
                    Component.translatable(
                            mode == Mode.DESCRIPTION
                                    ? "friendai.menu.description.language"
                                    : "friendai.menu.skills.trigger_description"),
                    left,
                    58,
                    Ui.DIM);
            if (mode == Mode.SKILL)
                Ui.text(g, font, Component.translatable("friendai.menu.skills.selection_hint"), left, 92, Ui.DIM);
        }
        Ui.text(
                g,
                font,
                Component.translatable(
                        mode == Mode.TRANSFER
                                ? "friendai.menu.skills.transfer_hint"
                                : mode == Mode.DESCRIPTION
                                        ? "friendai.menu.description.details"
                                        : "friendai.menu.skills.body"),
                left,
                bodyY - 10,
                Ui.DIM);
        if (current != null && mode == Mode.SKILL)
            Ui.text(
                    g,
                    font,
                    Ui.clip(
                            font,
                            (current.author() == null
                                            ? SkillLabels.value("BUILTIN").getString()
                                            : current.author().toString())
                                    + " · " + java.time.Instant.ofEpochMilli(current.updatedAt()) + " · v"
                                    + current.version() + " · "
                                    + SkillLabels.value(current.activation().name())
                                            .getString(),
                            w),
                    left,
                    height - 68,
                    Ui.DIM);
        if (!statusKey.isEmpty())
            Ui.text(g, font, Component.translatable(statusKey), left, height - (mode == Mode.SKILL ? 57 : 39), Ui.WARN);
    }
}
