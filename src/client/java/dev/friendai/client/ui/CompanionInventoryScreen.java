package dev.friendai.client.ui;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.menu.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The companion's inventory in the shape of the vanilla player inventory: armor in a column on the left, the
 * companion standing in the middle, its two hands under the model, health and hunger in vanilla icons, then the
 * companion's 27 slots, the viewer's inventory and hotbar. All sprites are the vanilla ones; the panel is drawn with
 * the same colours and bevel as the vanilla windows, so there is no custom texture to keep in sync.
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");

    private static final int PANEL = 0xFFC6C6C6;
    private static final int HIGHLIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int OUTLINE = 0xFF000000;
    private static final int ICON = 9;
    private static final int ICON_STEP = 8;
    private static final int ICONS = 10;
    private static final int VITALS_Y = 88;
    private static final int HEARTS_X = 8;
    private static final int FOOD_RIGHT = 168;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 254);
        this.inventoryLabelY = 162;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        drawPanel(graphics);
        for (Slot slot : menu.slots) {
            graphics.blitSprite(
                    RenderPipelines.GUI_TEXTURED, SLOT, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
        }
        CompanionEntity companion = menu.companion();
        if (companion == null) {
            return;
        }
        InventoryScreen.extractEntityInInventoryFollowsMouse(
                graphics,
                leftPos + 26,
                topPos + 18,
                leftPos + 75,
                topPos + 84,
                30,
                0.0625f,
                mouseX,
                mouseY,
                companion);
        drawVitals(graphics, companion);
    }

    /** The vanilla window body: a black outline, a light top-left bevel, grey fill and a dark bottom-right bevel. */
    private void drawPanel(GuiGraphicsExtractor graphics) {
        int x0 = leftPos;
        int y0 = topPos;
        int x1 = x0 + imageWidth;
        int y1 = y0 + imageHeight;
        graphics.fill(x0, y0, x1, y1, OUTLINE);
        graphics.fill(x0 + 1, y0 + 1, x1 - 1, y1 - 1, HIGHLIGHT);
        graphics.fill(x0 + 3, y0 + 3, x1 - 3, y1 - 3, PANEL);
        graphics.fill(x0 + 2, y1 - 3, x1 - 1, y1 - 1, SHADOW);
        graphics.fill(x1 - 3, y0 + 2, x1 - 1, y1 - 3, SHADOW);
    }

    /** Ten vanilla hearts on the left and ten food icons on the right, like the HUD. */
    private void drawVitals(GuiGraphicsExtractor graphics, CompanionEntity companion) {
        int y = topPos + VITALS_Y;
        float health = companion.getHealth();
        int hearts = Math.min(ICONS, (int) Math.ceil(companion.getMaxHealth() / 2.0f));
        for (int i = 0; i < ICONS; i++) {
            int x = leftPos + HEARTS_X + i * ICON_STEP;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, x, y, ICON, ICON);
            if (i >= hearts) {
                continue;
            }
            float remaining = health - i * 2.0f;
            if (remaining >= 2.0f) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x, y, ICON, ICON);
            } else if (remaining >= 1.0f) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, x, y, ICON, ICON);
            }
        }
        int food = companion.syncedFood();
        for (int i = 0; i < ICONS; i++) {
            // Right to left, as the vanilla HUD fills the hunger bar.
            int x = leftPos + FOOD_RIGHT - ICON - i * ICON_STEP;
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_EMPTY, x, y, ICON, ICON);
            int remaining = food - i * 2;
            if (remaining >= 2) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, y, ICON, ICON);
            } else if (remaining == 1) {
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, y, ICON, ICON);
            }
        }
    }
}
