package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.common.Components;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * A small vanilla-style window centered horizontally above the hotbar: a black frame with the same bevel and grey body
 * as the vanilla windows, a status dot and the state text. While recording the dot is red and pulses; while
 * transcribing three grey dots blink in turn; errors reuse the window with an orange dot.
 * Everything fades in and out, so the indicator never pops.
 */
public final class VoiceHud {
    private static final long ERROR_MILLIS = 3_000;

    private static final int HEIGHT = 18;
    private static final int PADDING = 6;
    private static final int INDICATOR_WIDTH = 10;
    private static final int INDICATOR_GAP = 4;
    private static final int DOT_SIZE = 4;
    private static final int SMALL_DOT = 2;
    private static final int SMALL_DOT_STEP = 4;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int TEXT_COLOR = 0xFF3F3F3F;
    private static final int ACCENT_RECORDING = 0xFFAA0000;
    private static final int ACCENT_TRANSCRIBING = 0xFF555555;
    private static final int ACCENT_ERROR = 0xFF8A4500;

    private static final float FADE_IN_PER_SECOND = 14f;
    private static final float FADE_OUT_PER_SECOND = 8f;

    private static VoiceState.Kind shownKind = VoiceState.Kind.IDLE;
    private static Component shownText;
    private static float fade;

    private VoiceHud() {}

    public static void register() {
        HudElementRegistry.addLast(FriendaiMod.id("voice"), VoiceHud::render);
    }

    private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
        float dt = Math.min(0.1f, Math.max(0f, delta.getRealtimeDeltaTicks()) / 20f);
        VoiceState.Snapshot state = VoiceState.current();
        boolean active = isActive(state);
        if (active) {
            shownKind = state.kind();
            shownText = textOf(state);
        }
        float target = active ? 1f : 0f;
        fade += (target - fade) * Math.min(1f, dt * (active ? FADE_IN_PER_SECOND : FADE_OUT_PER_SECOND));
        if (target == 0f && fade < 0.01f) {
            fade = 0f;
            return;
        }
        if (shownText == null) {
            return;
        }
        draw(graphics, Minecraft.getInstance().font, shownText, shownKind, fade);
    }

    private static boolean isActive(VoiceState.Snapshot state) {
        return switch (state.kind()) {
            case RECORDING, TRANSCRIBING -> true;
            case ERROR -> state.message() != null && System.currentTimeMillis() - state.sinceMillis() <= ERROR_MILLIS;
            default -> false;
        };
    }

    private static Component textOf(VoiceState.Snapshot state) {
        return switch (state.kind()) {
            case RECORDING -> Component.translatable("friendai.voice.hud.recording");
            case TRANSCRIBING -> Component.translatable("friendai.voice.hud.transcribing");
            case ERROR -> state.message() == null ? null : Components.of(state.message());
            default -> null;
        };
    }

    private static void draw(
            GuiGraphicsExtractor graphics, Font font, Component text, VoiceState.Kind kind, float visibility) {
        boolean transcribing = kind == VoiceState.Kind.TRANSCRIBING;
        boolean error = kind == VoiceState.Kind.ERROR;
        int width = PADDING * 2 + INDICATOR_WIDTH + INDICATOR_GAP + font.width(text);
        width = Math.min(width, graphics.guiWidth() - 16);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 44 - HEIGHT + Math.round((1f - visibility) * 4f);

        drawPanel(graphics, x, y, width, HEIGHT, visibility);

        int centerY = y + HEIGHT / 2;
        int contentX = x + PADDING;
        if (transcribing) {
            drawWaitingDots(graphics, contentX, centerY, visibility);
        } else {
            float strength = error ? 1f : pulse();
            drawDot(
                    graphics,
                    contentX + (INDICATOR_WIDTH - DOT_SIZE) / 2,
                    centerY,
                    error ? ACCENT_ERROR : ACCENT_RECORDING,
                    strength,
                    visibility);
        }

        int textColor = error ? ACCENT_ERROR : TEXT_COLOR;
        graphics.text(
                font,
                text.getVisualOrderText(),
                contentX + INDICATOR_WIDTH + INDICATOR_GAP,
                y + (HEIGHT - font.lineHeight) / 2,
                withAlpha(textColor, visibility),
                true);
    }

    /** The vanilla window body: a black outline, a light top-left bevel, a grey fill and a dark bottom-right bevel. */
    private static void drawPanel(
            GuiGraphicsExtractor graphics, int x, int y, int width, int height, float visibility) {
        graphics.fill(x, y, x + width, y + height, withAlpha(OUTLINE, visibility));
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, withAlpha(HIGHLIGHT, visibility));
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, withAlpha(PANEL, visibility));
        graphics.fill(x + 2, y + height - 2, x + width - 2, y + height - 1, withAlpha(SHADOW, visibility));
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, withAlpha(SHADOW, visibility));
    }

    /** A small solid dot in the accent colour; {@code strength} dims it, which makes it pulse. */
    private static void drawDot(
            GuiGraphicsExtractor graphics, int x, int centerY, int color, float strength, float visibility) {
        int top = centerY - DOT_SIZE / 2;
        graphics.fill(x, top, x + DOT_SIZE, top + DOT_SIZE, withAlpha(OUTLINE, visibility));
        graphics.fill(x + 1, top + 1, x + DOT_SIZE - 1, top + DOT_SIZE - 1, withAlpha(color, visibility * strength));
    }

    /** Three pixel squares that light up in turn while speech recognition runs. */
    private static void drawWaitingDots(GuiGraphicsExtractor graphics, int startX, int centerY, float visibility) {
        double seconds = System.nanoTime() / 1e9;
        int top = centerY - SMALL_DOT / 2;
        for (int i = 0; i < 3; i++) {
            float phase = 0.35f + 0.65f * (0.5f + 0.5f * (float) Math.sin(seconds * 7.0 - i * 1.1));
            int x = startX + i * SMALL_DOT_STEP;
            graphics.fill(x, top, x + SMALL_DOT, top + SMALL_DOT, withAlpha(ACCENT_TRANSCRIBING, visibility * phase));
        }
    }

    /** The recording dot's brightness: a steady red pulse, so the window never looks frozen. */
    private static float pulse() {
        double seconds = System.nanoTime() / 1e9;
        return 0.45f + 0.55f * (0.5f + 0.5f * (float) Math.sin(seconds * 5.2));
    }

    private static int withAlpha(int argb, float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
