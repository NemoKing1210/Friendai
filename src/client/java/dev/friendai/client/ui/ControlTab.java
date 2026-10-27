package dev.friendai.client.ui;

import dev.friendai.client.debug.MovementDebugState;
import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.CompanionVitals;
import dev.friendai.debug.RequestType;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** The companion at a glance (identity, state, vitals, current work) and its four actions. */
final class ControlTab implements MenuTab {
    private static final int GAP = 6;
    private static final int BUTTON_HEIGHT = 20;
    private static final int HEADER_HEIGHT = 52;
    private static final int VITALS_HEIGHT = 64;
    private static final int PAD = 8;

    private FlatButton summon;
    private FlatButton teleport;
    private FlatButton dismiss;
    private FlatButton stop;
    private FlatButton movementDebug;
    private FlatButton autoRespawn;
    private int buttonsTop;

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        boolean oneRow = width >= 4 * 70 + 3 * GAP;
        int rows = oneRow ? 1 : 2;
        int columns = oneRow ? 4 : 2;
        int buttonWidth = (width - (columns - 1) * GAP) / columns;
        buttonsTop = top + height - rows * BUTTON_HEIGHT - (rows - 1) * GAP;
        summon = button(host, "friendai.menu.summon", RequestType.SUMMON, Ui.GOOD, left, buttonsTop, buttonWidth);
        teleport = button(host, "friendai.menu.teleport", RequestType.TELEPORT, Ui.ACCENT,
                left + (buttonWidth + GAP), buttonsTop, buttonWidth);
        int secondRow = oneRow ? buttonsTop : buttonsTop + BUTTON_HEIGHT + GAP;
        int dismissX = oneRow ? left + 2 * (buttonWidth + GAP) : left;
        dismiss = button(host, "friendai.menu.dismiss", RequestType.DISMISS, Ui.WARN, dismissX, secondRow, buttonWidth);
        stop = button(host, "friendai.menu.stop", RequestType.STOP, Ui.BAD,
                dismissX + buttonWidth + GAP, secondRow, buttonWidth);
        movementDebug = host.add(new FlatButton(left, buttonsTop - GAP - BUTTON_HEIGHT, width, BUTTON_HEIGHT,
                Component.empty(), Ui.PURPLE, ControlTab::toggleMovementDebug));
        autoRespawn = host.add(new FlatButton(left, buttonsTop - 2 * (GAP + BUTTON_HEIGHT), width, BUTTON_HEIGHT,
                Component.empty(), Ui.ACCENT, ControlTab::toggleAutoRespawn));
        tick();
    }

    private static void toggleMovementDebug() {
        boolean on = !MovementDebugState.enabled();
        MovementDebugState.setEnabled(on);
        CompanionRequests.send(on ? RequestType.MOVE_DEBUG_ON : RequestType.MOVE_DEBUG_OFF);
    }

    private static void toggleAutoRespawn() {
        CompanionClientState.snapshot().ifPresent(snapshot -> CompanionRequests.send(
                RequestType.SET_AUTO_RESPAWN, Boolean.toString(!snapshot.autoRespawn()), ""));
    }

    private static FlatButton button(Host host, String key, RequestType request, int color, int x, int y, int width) {
        return host.add(new FlatButton(x, y, width, BUTTON_HEIGHT, Component.translatable(key), color,
                () -> CompanionRequests.send(request)));
    }

    @Override
    public void tick() {
        var snapshot = CompanionClientState.snapshot();
        boolean present = snapshot.map(CompanionSnapshot::present).orElse(false);
        boolean connected = CompanionRequests.serverSupported() && !CompanionClientState.isStale();
        summon.active = connected && !present;
        teleport.active = connected && present;
        dismiss.active = connected && present;
        stop.active = connected && present;
        boolean operator = CompanionClientState.snapshot().map(CompanionSnapshot::operator).orElse(false);
        movementDebug.visible = operator;
        movementDebug.active = connected;
        movementDebug.setMessage(Component.translatable(MovementDebugState.enabled()
                ? "friendai.menu.movement_debug.on"
                : "friendai.menu.movement_debug.off"));
        autoRespawn.visible = present;
        autoRespawn.active = connected && present && snapshot.map(CompanionSnapshot::autoRespawnAvailable).orElse(false);
        String autoRespawnKey = snapshot.map(s -> !s.autoRespawnAvailable()
                        ? "friendai.menu.auto_respawn.hardcore"
                        : s.autoRespawn() ? "friendai.menu.auto_respawn.on" : "friendai.menu.auto_respawn.off")
                .orElse("friendai.menu.auto_respawn.off");
        autoRespawn.setMessage(Component.translatable(autoRespawnKey));
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        var snapshot = CompanionClientState.snapshot();
        if (snapshot.isEmpty()) {
            return;
        }
        CompanionSnapshot s = snapshot.get();
        drawHeader(g, font, s, left, top, width);
        int y = top + HEADER_HEIGHT + GAP;
        int limit = buttonsTop - GAP
                - (movementDebug.visible ? BUTTON_HEIGHT + GAP : 0)
                - (autoRespawn.visible ? BUTTON_HEIGHT + GAP : 0);
        if (s.present() && limit - y >= VITALS_HEIGHT + GAP + 40) {
            drawVitals(g, font, s.vitals(), left, y, width);
            y += VITALS_HEIGHT + GAP;
        }
        drawActivity(g, font, s, left, y, width, limit - y);
    }

    private void drawHeader(GuiGraphicsExtractor g, Font font, CompanionSnapshot s, int left, int top, int width) {
        Ui.card(g, left, top, width, HEADER_HEIGHT);
        int stateColor = !s.present() ? Ui.DIM : s.thinking() ? Ui.WARN : Ui.GOOD;
        int ax = left + PAD;
        int ay = top + PAD;
        g.fill(ax, ay, ax + 36, ay + 36, Ui.alpha(stateColor, 0x30));
        Ui.border(g, ax, ay, 36, 36, Ui.alpha(stateColor, 0xC0));
        String initial = s.present() && !s.companionName().isEmpty()
                ? s.companionName().substring(0, 1).toUpperCase(java.util.Locale.ROOT)
                : "?";
        g.pose().pushMatrix();
        g.pose().translate(ax + 18, ay + 18 - font.lineHeight);
        g.pose().scale(2f, 2f);
        g.text(font, initial, -font.width(initial) / 2, 0, stateColor);
        g.pose().popMatrix();

        int textX = ax + 36 + PAD;
        int textWidth = left + width - PAD - textX;
        Component stateLabel = Component.translatable(!s.present()
                ? "friendai.menu.state.offline"
                : s.thinking() ? "friendai.menu.state.thinking" : "friendai.menu.state.idle");
        int badgeWidth = Ui.badgeWidth(font, stateLabel.getString());
        String name = s.present() ? s.companionName() : Component.translatable("friendai.menu.no_companion").getString();
        g.text(font, Ui.clip(font, name, Math.max(20, textWidth - badgeWidth - 6)), textX, top + PAD + 1, Ui.TEXT);
        Ui.badge(g, font, stateLabel.getString(), left + width - PAD - badgeWidth, top + PAD - 1, stateColor);
        String model = Component.translatable("friendai.menu.model", s.model(), s.provider()).getString();
        g.text(font, Ui.clip(font, model, textWidth), textX, top + PAD + 13, Ui.DIM);
        if (s.present()) {
            int x = textX;
            CompanionVitals v = s.vitals();
            x += Ui.badge(g, font, Component.translatable("friendai.menu.mode.combat",
                    combatName(v.protection(), v.style())).getString(), x, top + PAD + 25, Ui.PURPLE) + 4;
            Ui.badge(g, font, Component.translatable("friendai.menu.mode.pace", paceName(v.paceMode())).getString(),
                    x, top + PAD + 25, Ui.ACCENT);
        }
    }

    private void drawVitals(GuiGraphicsExtractor g, Font font, CompanionVitals v, int left, int top, int width) {
        Ui.card(g, left, top, width, VITALS_HEIGHT);
        int labelWidth = Math.min(80, Math.max(font.width(Component.translatable("friendai.menu.vital.health")),
                Math.max(font.width(Component.translatable("friendai.menu.vital.hunger")),
                        font.width(Component.translatable("friendai.menu.vital.inventory")))));
        int valueWidth = font.width("00.0 / 00.0") ;
        int barX = left + PAD + labelWidth + 6;
        int barWidth = Math.max(20, left + width - PAD - valueWidth - 6 - barX);
        row(g, font, "friendai.menu.vital.health", v.health() / Math.max(1f, v.maxHealth()), Ui.BAD,
                trim(v.health()) + " / " + trim(v.maxHealth()), left, top + 7, barX, barWidth, width);
        row(g, font, "friendai.menu.vital.hunger", v.food() / 20f, Ui.WARN,
                v.food() + " / 20", left, top + 21, barX, barWidth, width);
        row(g, font, "friendai.menu.vital.inventory", v.inventoryUsed() / (float) Math.max(1, v.inventorySlots()),
                Ui.ACCENT, v.inventoryUsed() + " / " + v.inventorySlots(), left, top + 35, barX, barWidth, width);
        row(g, font, "friendai.menu.vital.experience", v.xpProgress(), Ui.GOOD,
                Component.translatable("friendai.menu.vital.level", v.xpLevel()).getString(),
                left, top + 49, barX, barWidth, width);
    }

    private static void row(GuiGraphicsExtractor g, Font font, String labelKey, float fraction, int color,
            String value, int left, int y, int barX, int barWidth, int width) {
        g.text(font, Component.translatable(labelKey), left + PAD, y, Ui.DIM);
        Ui.bar(g, barX, y + 1, barWidth, 7, fraction, color);
        g.text(font, value, left + width - PAD - font.width(value), y, Ui.TEXT);
    }

    private static String trim(float value) {
        return value == Math.floor(value) ? String.valueOf((int) value) : String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private void drawActivity(GuiGraphicsExtractor g, Font font, CompanionSnapshot s, int left, int top, int width, int height) {
        if (height < 24) {
            return;
        }
        Ui.card(g, left, top, width, height);
        Ui.heading(g, font, Component.translatable("friendai.menu.activity"), left + PAD, top + 6, width - 2 * PAD);
        g.enableScissor(left + 1, top + 18, left + width - 1, top + height - 1);
        String text = s.present() ? s.currentTask() : "";
        CompanionVitals v = s.vitals();
        if (s.present() && v.action() != null && !v.action().isEmpty() && !"none".equals(v.action())) {
            String active = Component.translatable("friendai.menu.combat.active",
                    actionName(v.action()), reasonName(v.reason())).getString();
            text = active + (text.isEmpty() ? "" : "\n" + text);
        }
        QueuePanel.draw(g, font, text, left + PAD, top + 20, width - 2 * PAD);
        g.disableScissor();
    }

    static Component combatName(String protection, String style) {
        String protectionName = Component.translatable(switch (protection) {
            case "self_only" -> "friendai.menu.protection.self_only";
            case "guard" -> "friendai.menu.protection.guard";
            case "avoid_combat" -> "friendai.menu.protection.avoid_combat";
            default -> "friendai.menu.protection.bodyguard";
        }).getString();
        String styleName = Component.translatable(switch (style) {
            case "cautious" -> "friendai.menu.style.cautious";
            case "melee" -> "friendai.menu.style.melee";
            case "ranged" -> "friendai.menu.style.ranged";
            default -> "friendai.menu.style.balanced";
        }).getString();
        return Component.literal(protectionName + " / " + styleName);
    }

    private static String actionName(String action) {
        return Component.translatable(switch (action) {
            case "attack" -> "friendai.menu.action.attack";
            case "melee_approach" -> "friendai.menu.action.melee_approach";
            case "keep_range" -> "friendai.menu.action.keep_range";
            case "shield" -> "friendai.menu.action.shield";
            case "dodge" -> "friendai.menu.action.dodge";
            case "seek_cover" -> "friendai.menu.action.seek_cover";
            case "retreat" -> "friendai.menu.action.retreat";
            case "eat" -> "friendai.menu.action.eat";
            case "fire_bow" -> "friendai.menu.action.fire_bow";
            case "throw_potion" -> "friendai.menu.action.throw_potion";
            case "disengage" -> "friendai.menu.action.disengage";
            case "avoid" -> "friendai.menu.action.avoid";
            default -> "friendai.menu.action.none";
        }).getString();
    }

    private static String reasonName(String reason) {
        return Component.translatable(switch (reason) {
            case "imminent_danger" -> "friendai.menu.reason.imminent_danger";
            case "block_projectile" -> "friendai.menu.reason.block_projectile";
            case "block_melee" -> "friendai.menu.reason.block_melee";
            case "evade_projectile" -> "friendai.menu.reason.evade_projectile";
            case "evade_melee" -> "friendai.menu.reason.evade_melee";
            case "survive" -> "friendai.menu.reason.survive";
            case "recover" -> "friendai.menu.reason.recover";
            case "defend_self" -> "friendai.menu.reason.defend_self";
            case "defend_owner" -> "friendai.menu.reason.defend_owner";
            case "guard" -> "friendai.menu.reason.guard";
            case "attack" -> "friendai.menu.reason.attack";
            case "position" -> "friendai.menu.reason.position";
            case "out_of_pursuit" -> "friendai.menu.reason.out_of_pursuit";
            case "style_range" -> "friendai.menu.reason.style_range";
            case "avoid_combat" -> "friendai.menu.reason.avoid_combat";
            case "no_weapon" -> "friendai.menu.reason.no_weapon";
            case "no_ammunition" -> "friendai.menu.reason.no_ammunition";
            case "unsafe_lane" -> "friendai.menu.reason.unsafe_lane";
            default -> "friendai.menu.reason.none";
        }).getString();
    }

    static Component paceName(String mode) {
        return Component.translatable(switch (mode) {
            case "walk" -> "friendai.menu.pace.walk";
            case "run" -> "friendai.menu.pace.run";
            default -> "friendai.menu.pace.auto";
        });
    }
}
