package dev.friendai.client.ui;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.menu.CompanionInventoryMenu;
import dev.friendai.presentation.layout.CompanionInventoryLayout;
import dev.friendai.presentation.layout.MenuTheme;
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
 * companion standing in the middle, its two hands beside the model, health and hunger in vanilla icons, then the
 * companion's 27 slots, the viewer's inventory and hotbar. All sprites are the vanilla ones; the panel is drawn with
 * the same colours and bevel as the vanilla windows, so there is no custom texture to keep in sync.
 *
 * <p>The geometry is not decided here: {@link CompanionInventoryLayout} holds every origin the menu also uses, so a
 * slot and the icons drawn around it cannot drift apart.
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
    private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
    private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
    private static final Identifier FOOD_EMPTY = Identifier.withDefaultNamespace("hud/food_empty");
    private static final Identifier FOOD_FULL = Identifier.withDefaultNamespace("hud/food_full");
    private static final Identifier FOOD_HALF = Identifier.withDefaultNamespace("hud/food_half");

    private static final int PANEL = MenuTheme.WINDOW_FACE;
    private static final int HIGHLIGHT = MenuTheme.WINDOW_HIGHLIGHT;
    private static final int SHADOW = MenuTheme.WINDOW_SHADOW;
    private static final int OUTLINE = MenuTheme.WINDOW_OUTLINE;
    private static final int XP_GREEN = 0xFF80FF20;
    private static final int XP_BAR_BACK = 0xFF555555;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, CompanionInventoryLayout.WIDTH, CompanionInventoryLayout.HEIGHT);
        this.titleLabelX = CompanionInventoryLayout.LABEL_X;
        this.inventoryLabelX = CompanionInventoryLayout.LABEL_X;
        this.inventoryLabelY = CompanionInventoryLayout.LABEL_Y;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        drawPanel(graphics);
        for (Slot slot : menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, leftPos + slot.x - 1, topPos + slot.y - 1, 18, 18);
        }
        CompanionEntity companion = menu.companion();
        if (companion == null) {
            return;
        }
        InventoryScreen.extractEntityInInventoryFollowsMouse(
                graphics,
                leftPos + CompanionInventoryLayout.PORTRAIT_X,
                topPos + CompanionInventoryLayout.PORTRAIT_Y,
                leftPos + CompanionInventoryLayout.PORTRAIT_X + CompanionInventoryLayout.PORTRAIT_WIDTH,
                topPos + CompanionInventoryLayout.PORTRAIT_Y + CompanionInventoryLayout.PORTRAIT_HEIGHT,
                30,
                0.0625f,
                mouseX,
                mouseY,
                companion);
        drawVitals(graphics, companion);
        drawExperience(graphics, companion);
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

    /**
     * Ten vanilla hearts on the left and ten food icons on the right, like the HUD, as two groups with a gap between
     * them. A creative companion is invulnerable and does not go hungry, so those icons would show values that cannot
     * change; the row keeps its place either way, so the window does not jump between modes.
     */
    private void drawVitals(GuiGraphicsExtractor graphics, CompanionEntity companion) {
        if (!companion.creativeMode()) {
            int y = topPos + CompanionInventoryLayout.VITALS_Y;
            int icons = CompanionInventoryLayout.ICONS;
            int icon = CompanionInventoryLayout.ICON;
            int step = CompanionInventoryLayout.ICON_STEP;
            float health = companion.getHealth();
            int hearts = Math.min(icons, (int) Math.ceil(companion.getMaxHealth() / 2.0f));
            for (int i = 0; i < icons; i++) {
                int x = leftPos + CompanionInventoryLayout.heartsX() + i * step;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, x, y, icon, icon);
                if (i >= hearts) {
                    continue;
                }
                float remaining = health - i * 2.0f;
                if (remaining >= 2.0f) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, x, y, icon, icon);
                } else if (remaining >= 1.0f) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, x, y, icon, icon);
                }
            }
            int food = companion.syncedFood();
            for (int i = 0; i < icons; i++) {
                // Right to left, as the vanilla HUD fills the hunger bar.
                int x = leftPos + CompanionInventoryLayout.foodRight() - icon - i * step;
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_EMPTY, x, y, icon, icon);
                int remaining = food - i * 2;
                if (remaining >= 2) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_FULL, x, y, icon, icon);
                } else if (remaining == 1) {
                    graphics.blitSprite(RenderPipelines.GUI_TEXTURED, FOOD_HALF, x, y, icon, icon);
                }
            }
        }
    }

    /** A thin bar under the vitals, and the level beside the portrait, in the vanilla XP green. */
    private void drawExperience(GuiGraphicsExtractor graphics, CompanionEntity companion) {
        int x0 = leftPos + CompanionInventoryLayout.heartsX();
        int x1 = leftPos + CompanionInventoryLayout.foodRight();
        int y = topPos + CompanionInventoryLayout.XP_Y;
        int height = CompanionInventoryLayout.XP_HEIGHT;
        graphics.fill(x0, y, x1, y + height, XP_BAR_BACK);
        int filled = Math.round((x1 - x0) * Math.max(0.0f, Math.min(1.0f, companion.syncedXpProgress())));
        graphics.fill(x0, y, x0 + filled, y + height, XP_GREEN);
        graphics.text(
                font,
                Component.translatable("friendai.menu.vital.level", companion.syncedXpLevel()),
                leftPos + CompanionInventoryLayout.LEVEL_X,
                topPos + CompanionInventoryLayout.LEVEL_Y,
                XP_GREEN);
    }
}
