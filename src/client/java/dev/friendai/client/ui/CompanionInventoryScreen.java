package dev.friendai.client.ui;

import dev.friendai.menu.CompanionInventoryMenu;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

/**
 * The companion's inventory: six equipment slots, its 27 inventory slots and the owner's own inventory. Drawn as a
 * plain panel with the vanilla slot sprite, so there is no custom texture to keep in sync.
 */
public final class CompanionInventoryScreen extends AbstractContainerScreen<CompanionInventoryMenu> {
    private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
    private static final int PANEL = 0xFFC6C6C6;
    private static final int BORDER = 0xFF373737;

    public CompanionInventoryScreen(CompanionInventoryMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, 176, 202);
        this.inventoryLabelY = 108;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        super.extractBackground(graphics, mouseX, mouseY, a);
        int x = leftPos;
        int y = topPos;
        graphics.fill(x - 1, y - 1, x + imageWidth + 1, y + imageHeight + 1, BORDER);
        graphics.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        for (Slot slot : menu.slots) {
            graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x + slot.x - 1, y + slot.y - 1, 18, 18);
        }
    }
}
