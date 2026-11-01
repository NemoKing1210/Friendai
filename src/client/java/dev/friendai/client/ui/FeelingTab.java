package dev.friendai.client.ui;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.social.Mood;
import dev.friendai.presentation.companion.CompanionSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

/**
 * What the companion feels and what it is working on: its mood, the bond the owner has earned with it, the primary
 * goal and every criterion of that goal as met or not proven yet. Everything but the mood arrives in the owner's
 * snapshot; the mood is the one accessor the client reads, {@link CompanionEntity#syncedMood()}.
 */
final class FeelingTab extends StatefulMenuTab {
    private static final int PAD = 8;
    private static final int DETAIL = 10;
    private static final int MAX_REASONS = 3;

    private final ScrollView view = new ScrollView(false);

    private CompanionSnapshot builtFor;
    private Mood builtMood;
    private int builtWidth = -1;
    private List<Line> lines = List.of();

    private record Row(Component text, int indent) {}

    private record Line(FormattedCharSequence text, int indent) {}

    @Override
    public void init(Host host, int left, int top, int width, int height) {
        // Text only: the page is read straight from the snapshot, so there is no widget to place.
    }

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Ui.well(g, left, top, width, height);
        CompanionSnapshot snapshot = state.snapshot().orElse(null);
        Mood mood = moodOf();
        int inner = width - 2 * PAD;
        if (snapshot != builtFor || mood != builtMood || inner != builtWidth) {
            lines = wrap(font, rows(snapshot, mood), inner);
            builtFor = snapshot;
            builtMood = mood;
            builtWidth = inner;
        }
        int lineHeight = font.lineHeight + 1;
        view.begin(g, left + 1, top + 1, width - 2, height - 2, lines.size() * lineHeight + 2 * PAD);
        int y = view.top() + PAD;
        for (Line line : lines) {
            if (view.visible(y, lineHeight)) {
                Ui.text(g, font, line.text(), left + PAD + line.indent(), y, Ui.ON_WELL);
            }
            y += lineHeight;
        }
        view.end(g);
    }

    @Override
    public boolean scroll(double x, double y, double amount) {
        return view.contains(x, y) && view.scroll(amount);
    }

    private static List<Row> rows(CompanionSnapshot snapshot, Mood mood) {
        List<Row> rows = new ArrayList<>();
        if (mood != null) {
            rows.add(new Row(Component.translatable("friendai.menu.feeling.mood", moodLabel(mood)), 0));
        }
        if (snapshot == null) {
            return rows;
        }
        if (!snapshot.bondTier().isBlank()) {
            rows.add(new Row(Component.translatable("friendai.menu.feeling.bond", tierLabel(snapshot.bondTier())), 0));
        }
        int reasons = Math.min(MAX_REASONS, snapshot.bondReasons().size());
        for (int i = 0; i < reasons; i++) {
            rows.add(new Row(Component.literal(snapshot.bondReasons().get(i)), DETAIL));
        }
        if (!snapshot.goalText().isBlank()) {
            rows.add(new Row(Component.translatable("friendai.menu.feeling.goal", snapshot.goalText()), 0));
        }
        for (String criterion : snapshot.goalCriteria()) {
            String key = snapshot.goalUnmet().contains(criterion)
                    ? "friendai.menu.feeling.not_proven"
                    : "friendai.menu.feeling.met";
            rows.add(new Row(Component.translatable(key, criterion), DETAIL));
        }
        if (rows.isEmpty()) {
            rows.add(new Row(Component.translatable("friendai.menu.feeling.empty"), 0));
        }
        return rows;
    }

    private static List<Line> wrap(Font font, List<Row> rows, int width) {
        List<Line> lines = new ArrayList<>();
        for (Row row : rows) {
            for (FormattedCharSequence seq : font.split(row.text(), Math.max(1, width - row.indent()))) {
                lines.add(new Line(seq, row.indent()));
            }
        }
        return lines;
    }

    /** The owner's own companion while it is in tracking range, or null when the client cannot see it. */
    private static Mood moodOf() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            return null;
        }
        String owner = minecraft.player.getUUID().toString();
        for (var entity : minecraft.level.entitiesForRendering()) {
            if (entity instanceof CompanionEntity companion && owner.equals(companion.syncedOwner())) {
                return companion.syncedMood();
            }
        }
        return null;
    }

    private static Component moodLabel(Mood mood) {
        return switch (mood) {
            case CALM -> Component.translatable("friendai.social.mood.calm");
            case FOCUSED -> Component.translatable("friendai.social.mood.focused");
            case WORRIED -> Component.translatable("friendai.social.mood.worried");
            case PLEASED -> Component.translatable("friendai.social.mood.pleased");
            case DISPLEASED -> Component.translatable("friendai.social.mood.displeased");
            case COMBAT -> Component.translatable("friendai.social.mood.combat");
        };
    }

    private static Component tierLabel(String tier) {
        return switch (tier) {
            case "stranger" -> Component.translatable("friendai.social.tier.stranger");
            case "acquaintance" -> Component.translatable("friendai.social.tier.acquaintance");
            case "friend" -> Component.translatable("friendai.social.tier.friend");
            case "close" -> Component.translatable("friendai.social.tier.close");
            default -> Component.literal(tier);
        };
    }
}
