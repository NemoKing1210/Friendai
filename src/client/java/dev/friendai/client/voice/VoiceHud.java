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
 * The recorder as a capsule centered horizontally in the lower third of the screen, above the hotbar: a pulsing dot,
 * the state text and, while recording, bars that follow the microphone's loudness. Errors use the same capsule in
 * orange. Everything fades in and out, so the indicator never pops.
 */
public final class VoiceHud {
    private static final long ERROR_MILLIS = 3_000;

    private static final int HEIGHT = 22;
    private static final int PADDING = 9;
    private static final int RADIUS = 7;
    private static final int SIDE_GAP = 10;
    private static final int DOT = 6;

    private static final int BARS = 5;
    private static final int BAR_WIDTH = 3;
    private static final int BAR_GAP = 2;
    private static final int BAR_MIN_HEIGHT = 3;
    private static final int BAR_MAX_HEIGHT = 12;
    private static final int TRANSCRIBE_DOTS_WIDTH = 12;

    private static final int TEXT_COLOR = 0xFFF5F5F7;
    private static final int ACCENT_RECORDING = 0xFFFF5A5F;
    private static final int ACCENT_TRANSCRIBING = 0xFFD7D7DE;
    private static final int ACCENT_ERROR = 0xFFFFA62B;
    private static final int BAR_LOW = 0xFF6E6E7A;
    private static final int BACKGROUND = 0xE614141B;

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
        int accent = switch (kind) {
            case RECORDING -> ACCENT_RECORDING;
            case ERROR -> ACCENT_ERROR;
            default -> ACCENT_TRANSCRIBING;
        };
        boolean recording = kind == VoiceState.Kind.RECORDING;
        boolean transcribing = kind == VoiceState.Kind.TRANSCRIBING;
        int leadWidth = kind == VoiceState.Kind.ERROR ? 0 : DOT + SIDE_GAP;
        int tailWidth = recording ? BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP : transcribing ? TRANSCRIBE_DOTS_WIDTH : 0;
        int width = PADDING * 2 + leadWidth + font.width(text) + (tailWidth > 0 ? SIDE_GAP + tailWidth : 0);
        width = Math.min(width, graphics.guiWidth() - 16);
        int x = (graphics.guiWidth() - width) / 2;
        int y = graphics.guiHeight() - 44 - HEIGHT + Math.round((1f - visibility) * 4f);

        roundedRect(graphics, x - 2, y - 2, width + 4, HEIGHT + 4, RADIUS + 2, withAlpha(accent, visibility * 0.16f));
        roundedRect(graphics, x, y, width, HEIGHT, RADIUS, withAlpha(accent, visibility * 0.65f));
        roundedRect(graphics, x + 1, y + 1, width - 2, HEIGHT - 2, RADIUS - 1, withAlpha(BACKGROUND, visibility));

        int centerY = y + HEIGHT / 2;
        int contentX = x + PADDING;
        if (kind != VoiceState.Kind.ERROR) {
            double seconds = System.nanoTime() / 1e9;
            float pulse = 0.5f + 0.5f * (float) Math.sin(seconds * 5.2);
            float dotAlpha = recording ? 0.55f + 0.45f * pulse : 0.7f;
            fillCircle(graphics, contentX + DOT / 2, centerY, recording ? 3 : 2, withAlpha(accent, visibility * dotAlpha));
        }

        graphics.text(
                font,
                text.getVisualOrderText(),
                contentX + leadWidth,
                y + (HEIGHT - font.lineHeight) / 2,
                withAlpha(TEXT_COLOR, visibility));

        if (recording) {
            drawBars(graphics, x + width - PADDING - (BARS * BAR_WIDTH + (BARS - 1) * BAR_GAP), y + HEIGHT - 5, visibility);
        } else if (transcribing) {
            drawWaitingDots(graphics, x + width - PADDING - TRANSCRIBE_DOTS_WIDTH, centerY, visibility);
        }
    }

    /** Five bars whose height follows the microphone, with a little sway so silence does not look frozen. */
    private static void drawBars(GuiGraphicsExtractor graphics, int startX, int bottom, float visibility) {
        for (int i = 0; i < BARS; i++) {
            int height = BAR_MIN_HEIGHT + Math.round(levels[i] * (BAR_MAX_HEIGHT - BAR_MIN_HEIGHT));
            int color = withAlpha(mix(BAR_LOW, ACCENT_RECORDING, levels[i]), visibility);
            int barX = startX + i * (BAR_WIDTH + BAR_GAP);
            graphics.fill(barX + 1, bottom - height, barX + BAR_WIDTH - 1, bottom - height + 1, color);
            graphics.fill(barX, bottom - height + 1, barX + BAR_WIDTH, bottom, color);
        }
    }

    /** Three dots that light up in turn while speech recognition runs. */
    private static void drawWaitingDots(GuiGraphicsExtractor graphics, int startX, int centerY, float visibility) {
        double seconds = System.nanoTime() / 1e9;
        for (int i = 0; i < 3; i++) {
            float phase = 0.5f + 0.5f * (float) Math.sin(seconds * 7.0 - i * 1.1);
            fillCircle(graphics, startX + 1 + i * 5, centerY, 1, withAlpha(ACCENT_TRANSCRIBING, visibility * (0.25f + 0.75f * phase)));
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

    private static void roundedRect(GuiGraphicsExtractor graphics, int x, int y, int width, int height, int radius, int color) {
        int r = Math.max(0, Math.min(radius, Math.min(width, height) / 2));
        graphics.fill(x + r, y, x + width - r, y + height, color);
        graphics.fill(x, y + r, x + r, y + height - r, color);
        graphics.fill(x + width - r, y + r, x + width, y + height - r, color);
        for (int row = 0; row < r; row++) {
            int offset = r - (int) Math.floor(Math.sqrt((double) r * r - (double) (r - 1 - row) * (r - 1 - row)));
            graphics.fill(x + offset, y + row, x + width - offset, y + row + 1, color);
            graphics.fill(x + offset, y + height - 1 - row, x + width - offset, y + height - row, color);
        }
    }

    private static void fillCircle(GuiGraphicsExtractor graphics, int centerX, int centerY, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int dx = (int) Math.sqrt((double) radius * radius - (double) dy * dy);
            graphics.fill(centerX - dx, centerY + dy, centerX + dx + 1, centerY + dy + 1, color);
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
