package dev.friendai.client.voice;

import dev.friendai.FriendaiMod;
import dev.friendai.core.Components;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/**
 * The recorder as a vanilla-style window centered horizontally in the lower third of the screen, above the hotbar:
 * a black frame with the same bevel and grey body as the vanilla windows, a pixel microphone, the state text and,
 * while recording, bars that follow the microphone's loudness. Errors reuse the same window with an orange mark.
 * Everything fades in and out, so the indicator never pops.
 */
public final class VoiceHud {
    private static final long ERROR_MILLIS = 3_000;

    private static final int HEIGHT = 36;
    private static final int PADDING = 8;
    private static final int ICON_WIDTH = 12;
    private static final int ICON_GAP = 8;
    private static final int SIDE_GAP = 8;

    private static final int BARS = 6;
    private static final int BAR_WIDTH = 4;
    private static final int BAR_GAP = 2;
    private static final int BAR_MIN_HEIGHT = 4;
    private static final int BAR_MAX_HEIGHT = 20;
    private static final int TRANSCRIBE_DOTS_WIDTH = 16;

    private static final int PANEL = 0xFFC6C6C6;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int TEXT_COLOR = 0xFF3F3F3F;
    private static final int ACCENT_RECORDING = 0xFFAA0000;
    private static final int ACCENT_TRANSCRIBING = 0xFF555555;
    private static final int ACCENT_ERROR = 0xFF8A4500;
    private static final int BAR_LOW = 0xFF8B8B8B;
    private static final int MIC_BODY = 0xFFE9E9E9;
    private static final int MIC_GRILLE = 0xFF8B8B8B;

    /** A 12x17 microphone: {@code #} is the outline, {@code a} the body and {@code b} the grille. */
    private static final String[] MIC = {
        "....####....",
        "...######...",
        "..########..",
        "..#ababab#..",
        "..#ababab#..",
        "..#ababab#..",
        "..#ababab#..",
        "..########..",
        "...######...",
        "....####....",
        "..#......#..",
        "..#......#..",
        "..#......#..",
        "..########..",
        ".....##.....",
        ".....##.....",
        "...######...",
    };

    private static final float FADE_IN_PER_SECOND = 14f;
    private static final float FADE_OUT_PER_SECOND = 8f;

    private static VoiceState.Kind shownKind = VoiceState.Kind.IDLE;
    private static Component shownText;
    private static float fade;
    private static final float[] levels = new float[BARS];

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
        updateLevels(dt);
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
        boolean recording = kind == VoiceState.Kind.RECORDING;
        boolean transcribing = kind == VoiceState.Kind.TRANSCRIBING;
        boolean error = kind == VoiceState.Kind.ERROR;
        int leadWidth = ICON_WIDTH + ICON_GAP;
        int tailWidth = recording ? BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP : transcribing ? TRANSCRIBE_DOTS_WIDTH : 0;
        int width = PADDING * 2 + leadWidth + font.width(text) + (tailWidth > 0 ? SIDE_GAP + tailWidth : 0);
        width = Math.min(width, graphics.guiWidth() - 16);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 44 - HEIGHT + Math.round((1f - visibility) * 4f);

        drawPanel(graphics, x, y, width, HEIGHT, visibility);

        int centerY = y + HEIGHT / 2;
        int contentX = x + PADDING;
        if (error) {
            drawWarning(graphics, contentX, centerY, visibility);
        } else {
            double seconds = System.nanoTime() / 1e9;
            float pulse = 0.5f + 0.5f * (float) Math.sin(seconds * 5.2);
            drawMic(graphics, contentX, centerY - MIC.length / 2, visibility, pulse, recording);
        }

        int textColor = error ? ACCENT_ERROR : TEXT_COLOR;
        graphics.text(
                font,
                text.getVisualOrderText(),
                contentX + leadWidth,
                y + (HEIGHT - font.lineHeight) / 2,
                withAlpha(textColor, visibility),
                true);

        if (recording) {
            drawBars(
                    graphics,
                    x + width - PADDING - (BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP),
                    y + HEIGHT - PADDING,
                    visibility);
        } else if (transcribing) {
            drawWaitingDots(graphics, x + width - PADDING - TRANSCRIBE_DOTS_WIDTH, centerY, visibility);
        }
    }

    /** The vanilla window body: a black outline, a light top-left bevel, a grey fill and a dark bottom-right bevel. */
    private static void drawPanel(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float visibility) {
        graphics.fill(x, y, x + width, y + height, withAlpha(OUTLINE, visibility));
        graphics.fill(x + 1, y + 1, x + width - 1, y + height - 1, withAlpha(HIGHLIGHT, visibility));
        graphics.fill(x + 2, y + 2, x + width - 2, y + height - 2, withAlpha(PANEL, visibility));
        graphics.fill(x + 2, y + height - 2, x + width - 2, y + height - 1, withAlpha(SHADOW, visibility));
        graphics.fill(x + width - 2, y + 2, x + width - 1, y + height - 2, withAlpha(SHADOW, visibility));
    }

    /** The microphone as chunky pixels; while recording the body pulses in the accent colour. */
    private static void drawMic(
            GuiGraphicsExtractor graphics, int x, int y, float visibility, float pulse, boolean recording) {
        float tint = recording ? 0.35f + 0.5f * pulse : 0f;
        int outline = withAlpha(OUTLINE, visibility);
        int body = withAlpha(mix(MIC_BODY, ACCENT_RECORDING, tint), visibility);
        int grille = withAlpha(mix(MIC_GRILLE, ACCENT_RECORDING, tint * 0.6f), visibility);
        for (int row = 0; row < MIC.length; row++) {
            String line = MIC[row];
            for (int col = 0; col < line.length(); col++) {
                int color = switch (line.charAt(col)) {
                    case '#' -> outline;
                    case 'a' -> body;
                    case 'b' -> grille;
                    default -> 0;
                };
                if (color != 0) {
                    graphics.fill(x + col, y + row, x + col + 1, y + row + 1, color);
                }
            }
        }
    }

    /** A blocky exclamation mark in the accent colour, for the error state. */
    private static void drawWarning(GuiGraphicsExtractor graphics, int x, int centerY, float visibility) {
        int outline = withAlpha(OUTLINE, visibility);
        int accent = withAlpha(ACCENT_ERROR, visibility);
        int top = centerY - 8;
        graphics.fill(x + 3, top, x + 9, top + 12, outline);
        graphics.fill(x + 4, top + 1, x + 8, top + 11, accent);
        graphics.fill(x + 3, top + 14, x + 9, top + 18, outline);
        graphics.fill(x + 4, top + 15, x + 8, top + 17, accent);
    }

    /** Six chunky bars whose height follows the microphone, with a little sway so silence does not look frozen. */
    private static void drawBars(GuiGraphicsExtractor graphics, int startX, int bottom, float visibility) {
        for (int i = 0; i < BARS; i++) {
            int steps = Math.round(levels[i] * (BAR_MAX_HEIGHT - BAR_MIN_HEIGHT) / 2f);
            int height = BAR_MIN_HEIGHT + steps * 2;
            int barX = startX + i * (BAR_WIDTH + BAR_GAP);
            graphics.fill(barX, bottom - height, barX + BAR_WIDTH, bottom, withAlpha(OUTLINE, visibility));
            graphics.fill(
                    barX + 1,
                    bottom - height + 1,
                    barX + BAR_WIDTH - 1,
                    bottom - 1,
                    withAlpha(mix(BAR_LOW, ACCENT_RECORDING, levels[i]), visibility));
        }
    }

    /** Three pixel squares that light up in turn while speech recognition runs. */
    private static void drawWaitingDots(GuiGraphicsExtractor graphics, int startX, int centerY, float visibility) {
        double seconds = System.nanoTime() / 1e9;
        int outline = withAlpha(OUTLINE, visibility);
        for (int i = 0; i < 3; i++) {
            float phase = 0.5f + 0.5f * (float) Math.sin(seconds * 7.0 - i * 1.1);
            int x = startX + i * 6;
            graphics.fill(x, centerY - 2, x + 4, centerY + 2, outline);
            graphics.fill(
                    x + 1,
                    centerY - 1,
                    x + 3,
                    centerY + 1,
                    withAlpha(mix(BAR_LOW, ACCENT_TRANSCRIBING, phase), visibility));
        }
    }

    private static void updateLevels(float dt) {
        float level = shownKind == VoiceState.Kind.RECORDING ? VoiceRuntime.mic().level() : 0f;
        double seconds = System.nanoTime() / 1e9;
        for (int i = 0; i < BARS; i++) {
            float sway = 0.6f + 0.4f * (float) Math.abs(Math.sin(seconds * 2.6 + i * 1.9));
            float goal = Math.min(1f, level * sway * 1.5f);
            float speed = goal > levels[i] ? 20f : 7f;
            levels[i] += (goal - levels[i]) * Math.min(1f, dt * speed);
        }
    }

    private static int mix(int from, int to, float amount) {
        float t = Math.max(0f, Math.min(1f, amount));
        int red = Math.round(((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * t);
        int green = Math.round(((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * t);
        int blue = Math.round((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * t);
        return (0xFF << 24) | (red << 16) | (green << 8) | blue;
    }

    private static int withAlpha(int argb, float factor) {
        float clamped = Math.max(0f, Math.min(1f, factor));
        int alpha = Math.round(((argb >>> 24) & 0xFF) * clamped);
        return (alpha << 24) | (argb & 0xFFFFFF);
    }
}
