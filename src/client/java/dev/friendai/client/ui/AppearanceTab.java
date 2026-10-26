package dev.friendai.client.ui;

import dev.friendai.client.skin.SkinSources;
import dev.friendai.debug.CompanionSnapshot;
import java.util.function.Consumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

/** The companion's name and skin: rename it, or pick a skin from a file, a Minecraft username or a URL. */
final class AppearanceTab implements MenuTab {
    private static final int FIELD = 20;
    private static final int GAP = 6;
    private static final int HEADING = 12;
    private static final int APPLY_WIDTH = 64;

    // Kept across tab switches and screen rebuilds.
    private static boolean slim;
    private static byte[] lastPng;
    private static Component status = Component.empty();
    private static int statusColor = Ui.DIM;
    private static String nameText = "";
    private static String userText = "";
    private static String urlText = "";

    private EditBox name;
    private FlatButton rename;
    private FlatButton file;
    private FlatButton userApply;
    private FlatButton urlApply;
    private FlatButton wide;
    private FlatButton slimButton;
    private FlatButton reset;
    private int statusY;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        int y = top + HEADING;
        name = host.add(new EditBox(host.font(), left, y, width - APPLY_WIDTH - GAP, FIELD,
                Component.translatable("friendai.menu.appearance.name_hint")));
        name.setMaxLength(32);
        name.setHint(Component.translatable("friendai.menu.appearance.name_hint"));
        name.setValue(nameText.isEmpty() ? currentName() : nameText);
        name.setResponder(s -> nameText = s);
        rename = host.add(new FlatButton(left + width - APPLY_WIDTH, y, APPLY_WIDTH, FIELD,
                Component.translatable("friendai.menu.appearance.rename"), Ui.GOOD,
                () -> CompanionRequests.rename(name.getValue())));

        y += FIELD + GAP + HEADING;
        file = host.add(new FlatButton(left, y, width, FIELD,
                Component.translatable("friendai.menu.appearance.choose_file"), Ui.ACCENT,
                () -> run(SkinSources::fromFile)));

        y += FIELD + GAP;
        EditBox user = host.add(new EditBox(host.font(), left, y, width - APPLY_WIDTH - GAP, FIELD,
                Component.translatable("friendai.menu.appearance.username_hint")));
        user.setMaxLength(16);
        user.setHint(Component.translatable("friendai.menu.appearance.username_hint"));
        user.setValue(userText);
        user.setResponder(s -> userText = s);
        userApply = host.add(new FlatButton(left + width - APPLY_WIDTH, y, APPLY_WIDTH, FIELD,
                Component.translatable("friendai.menu.appearance.apply"), Ui.ACCENT,
                () -> run(callback -> SkinSources.fromUsername(user.getValue(), callback))));

        y += FIELD + GAP;
        EditBox url = host.add(new EditBox(host.font(), left, y, width - APPLY_WIDTH - GAP, FIELD,
                Component.translatable("friendai.menu.appearance.url_hint")));
        url.setMaxLength(500);
        url.setHint(Component.translatable("friendai.menu.appearance.url_hint"));
        url.setValue(urlText);
        url.setResponder(s -> urlText = s);
        urlApply = host.add(new FlatButton(left + width - APPLY_WIDTH, y, APPLY_WIDTH, FIELD,
                Component.translatable("friendai.menu.appearance.apply"), Ui.ACCENT,
                () -> run(callback -> SkinSources.fromUrl(url.getValue(), callback))));

        y += FIELD + GAP;
        int third = (width - 2 * GAP) / 3;
        wide = host.add(new FlatButton(left, y, third, FIELD,
                Component.translatable("friendai.menu.appearance.wide"), Ui.ACCENT, () -> setSlim(false)));
        slimButton = host.add(new FlatButton(left + third + GAP, y, third, FIELD,
                Component.translatable("friendai.menu.appearance.slim"), Ui.ACCENT, () -> setSlim(true)));
        reset = host.add(new FlatButton(left + 2 * (third + GAP), y, width - 2 * (third + GAP), FIELD,
                Component.translatable("friendai.menu.appearance.reset"), Ui.WARN, AppearanceTab::resetSkin));
        statusY = y + FIELD + GAP;
        tick();
    }

    private static String currentName() {
        return CompanionClientState.snapshot()
                .filter(CompanionSnapshot::present)
                .map(CompanionSnapshot::companionName)
                .orElse("");
    }

    private static void run(Consumer<Consumer<SkinSources.Outcome>> source) {
        say(Component.translatable("friendai.menu.appearance.working"), Ui.DIM);
        source.accept(AppearanceTab::onOutcome);
    }

    private static void onOutcome(SkinSources.Outcome outcome) {
        if (outcome.picked() == null) {
            say(outcome.error(), Ui.BAD);
            return;
        }
        if (outcome.picked().slim() != null) {
            slim = outcome.picked().slim();
        }
        lastPng = outcome.picked().png();
        CompanionRequests.uploadSkin(lastPng, slim);
        say(Component.translatable("friendai.menu.appearance.applied"), Ui.GOOD);
    }

    private static void setSlim(boolean value) {
        slim = value;
        if (lastPng != null) {
            CompanionRequests.uploadSkin(lastPng, slim);
        }
    }

    private static void resetSkin() {
        lastPng = null;
        slim = false;
        CompanionRequests.uploadSkin(new byte[0], false);
        say(Component.translatable("friendai.menu.appearance.reset_done"), Ui.GOOD);
    }

    private static void say(Component message, int color) {
        status = message;
        statusColor = color;
    }

    @Override
    public void tick() {
        boolean present = CompanionClientState.snapshot().map(CompanionSnapshot::present).orElse(false);
        boolean connected = CompanionRequests.serverSupported() && !CompanionClientState.isStale() && present;
        boolean skins = connected && CompanionRequests.skinsSupported();
        rename.active = connected;
        for (FlatButton button : new FlatButton[] {file, userApply, urlApply, wide, slimButton, reset}) {
            button.active = skins;
        }
        wide.setColor(slim ? Ui.DIM : Ui.GOOD);
        slimButton.setColor(slim ? Ui.GOOD : Ui.DIM);
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Ui.heading(g, font, Component.translatable("friendai.menu.appearance.name"), left, top, width);
        Ui.heading(g, font, Component.translatable("friendai.menu.appearance.skin"), left,
                top + HEADING + FIELD + GAP, width);
        g.text(font, Ui.clip(font, status.getString(), width), left, statusY, statusColor);
    }
}
