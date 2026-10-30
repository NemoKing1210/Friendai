package dev.friendai.client.ui;

import dev.friendai.presentation.companion.TaskArgs;
import dev.friendai.presentation.companion.TaskArgs.Chip;
import dev.friendai.presentation.companion.TaskArgs.Kind;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

/**
 * Draws a task's arguments as a wrapped row of small pills: an item or block with its icon and game name, the
 * coordinates as coloured {@code X Y Z}, the count as {@code x24}, anything else as {@code key value}.
 */
final class TaskChips {
    static final int CHIP_HEIGHT = 18;
    private static final int ROW_GAP = 2;
    private static final int GAP = 3;
    private static final int PAD = 4;
    private static final int ICON = 16;
    private static final Pattern ID = Pattern.compile("\\b[a-z0-9_.-]+:[a-z0-9_./-]+");

    private static final int X_COLOR = 0xFFB03030;
    private static final int Y_COLOR = 0xFF2F7D32;
    private static final int Z_COLOR = 0xFF2A5DA8;

    private final List<Placed> placed = new ArrayList<>();
    private final int height;

    private record Placed(Chip chip, ItemStack stack, String name, int x, int row, int width) {}

    private TaskChips(List<Placed> placed, int height) {
        this.placed.addAll(placed);
        this.height = height;
    }

    /** Lays the chips of {@code rawArgs} out in rows no wider than {@code width}; empty when nothing is worth showing. */
    static TaskChips of(Font font, String rawArgs, int width) {
        List<Chip> chips = TaskArgs.parse(rawArgs);
        List<Placed> out = new ArrayList<>();
        int x = 0;
        int row = 0;
        for (Chip chip : chips) {
            ItemStack stack = chip.kind() == Kind.ITEM ? stackOf(chip.itemId()) : ItemStack.EMPTY;
            String name = chip.kind() == Kind.ITEM ? nameOf(chip.itemId(), stack) : "";
            int w = chipWidth(font, chip, stack, name);
            if (x > 0 && x + w > width) {
                row++;
                x = 0;
            }
            out.add(new Placed(chip, stack, name, x, row, Math.min(w, width)));
            x += w + GAP;
        }
        int rows = chips.isEmpty() ? 0 : row + 1;
        return new TaskChips(out, rows == 0 ? 0 : rows * CHIP_HEIGHT + (rows - 1) * ROW_GAP);
    }

    int height() {
        return height;
    }

    void paint(GuiGraphicsExtractor g, Font font, int left, int top) {
        for (Placed p : placed) {
            int x = left + p.x();
            int y = top + p.row() * (CHIP_HEIGHT + ROW_GAP);
            Ui.card(g, x, y, p.width(), CHIP_HEIGHT, Ui.CARD_HOVER, Ui.BORDER);
            int tx = x + PAD;
            int ty = y + (CHIP_HEIGHT - font.lineHeight) / 2 + 1;
            switch (p.chip().kind()) {
                case ITEM -> {
                    if (!p.stack().isEmpty()) {
                        g.fakeItem(p.stack(), tx - 1, y + 1);
                        tx += ICON;
                    }
                    Ui.text(g, font, Ui.clip(font, p.name(), x + p.width() - PAD - tx), tx, ty, Ui.TEXT);
                }
                case POS -> paintPosition(g, font, p.chip().text(), tx, ty);
                case COUNT -> Ui.text(g, font, "\u00d7" + p.chip().text(), tx, ty, Ui.TEXT);
                case TEXT -> {
                    if (!p.chip().label().isEmpty()) {
                        Ui.text(g, font, p.chip().label(), tx, ty, Ui.DIM);
                        tx += font.width(p.chip().label()) + 4;
                    }
                    Ui.text(g, font, Ui.clip(font, p.chip().text(), x + p.width() - PAD - tx), tx, ty, Ui.TEXT);
                }
            }
        }
    }

    /** The text with every {@code namespace:id} replaced by the game's name for that item or block. */
    static String humanize(String text) {
        Matcher m = ID.matcher(text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            String id = m.group();
            m.appendReplacement(out, Matcher.quoteReplacement(nameOf(id, stackOf(id))));
        }
        m.appendTail(out);
        return out.toString();
    }

    private static int chipWidth(Font font, Chip chip, ItemStack stack, String name) {
        return switch (chip.kind()) {
            case ITEM -> 2 * PAD + (stack.isEmpty() ? 0 : ICON) + font.width(name);
            case POS -> 2 * PAD + positionWidth(font, chip.text());
            case COUNT -> 2 * PAD + font.width("\u00d7" + chip.text());
            case TEXT ->
                2 * PAD + (chip.label().isEmpty() ? 0 : font.width(chip.label()) + 4) + font.width(chip.text());
        };
    }

    private static int positionWidth(Font font, String text) {
        String[] parts = text.split(" ");
        int w = 0;
        for (int i = 0; i < parts.length; i++) {
            w += font.width(AXES[i % 3] + " " + parts[i]) + (i > 0 ? 5 : 0);
        }
        return w;
    }

    private static final String[] AXES = {"X", "Y", "Z"};
    private static final int[] AXIS_COLORS = {X_COLOR, Y_COLOR, Z_COLOR};

    private static void paintPosition(GuiGraphicsExtractor g, Font font, String text, int x, int y) {
        String[] parts = text.split(" ");
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                x += 5;
            }
            Ui.text(g, font, AXES[i % 3], x, y, AXIS_COLORS[i % 3]);
            x += font.width(AXES[i % 3] + " ");
            Ui.text(g, font, parts[i], x, y, Ui.TEXT);
            x += font.width(parts[i]);
        }
    }

    private static ItemStack stackOf(String id) {
        Identifier key = Identifier.tryParse(id);
        if (key == null) {
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.getOptional(key).orElse(null);
        if (item == null) {
            Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            item = block == null ? null : block.asItem();
        }
        return item == null || item == net.minecraft.world.item.Items.AIR ? ItemStack.EMPTY : new ItemStack(item);
    }

    private static String nameOf(String id, ItemStack stack) {
        if (!stack.isEmpty()) {
            return stack.getHoverName().getString();
        }
        Identifier key = Identifier.tryParse(id);
        if (key != null) {
            Block block = BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
            if (block != null) {
                return block.getName().getString();
            }
        }
        return id;
    }
}
