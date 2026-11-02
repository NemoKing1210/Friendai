package dev.friendai.client.ui;

import dev.friendai.features.cartography.TerrainMapView;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

/** A compact explored-terrain view: cells, named sites and known roads, never the full grid. */
final class MapTab extends StatefulMenuTab {
    private static final int PAD = 10;
    private static final int DOT = 4;

    @Override
    public void init(Host host, int left, int top, int width, int height) {}

    @Override
    public void render(
            GuiGraphicsExtractor g, Font font, int left, int top, int width, int height, int mouseX, int mouseY) {
        Ui.well(g, left, top, width, height);
        TerrainMapView map = state == null ? TerrainMapView.EMPTY : state.terrainMap();
        int innerLeft = left + PAD;
        int innerTop = top + PAD;
        int innerWidth = width - 2 * PAD;
        int footer = font.lineHeight + 6;
        int areaHeight = height - 2 * PAD - footer;
        if (map.cells().isEmpty() && map.sites().isEmpty()) {
            Ui.text(
                    g,
                    font,
                    Component.translatable("friendai.menu.map.empty"),
                    innerLeft,
                    innerTop + areaHeight / 2 - font.lineHeight / 2,
                    Ui.ON_WELL_DIM);
            return;
        }
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (TerrainMapView.Cell cell : map.cells()) {
            minX = Math.min(minX, cell.x());
            maxX = Math.max(maxX, cell.x());
            minZ = Math.min(minZ, cell.z());
            maxZ = Math.max(maxZ, cell.z());
        }
        for (TerrainMapView.Site site : map.sites()) {
            minX = Math.min(minX, site.x());
            maxX = Math.max(maxX, site.x());
            minZ = Math.min(minZ, site.z());
            maxZ = Math.max(maxZ, site.z());
        }
        int spanX = Math.max(1, maxX - minX);
        int spanZ = Math.max(1, maxZ - minZ);
        double scale = Math.min((innerWidth - DOT) / (double) spanX, (areaHeight - DOT) / (double) spanZ);
        scale = Math.max(0.25, scale);
        for (TerrainMapView.Road road : map.roads()) {
            int ax = innerLeft + (int) ((road.fromX() - minX) * scale);
            int az = innerTop + (int) ((road.fromZ() - minZ) * scale);
            int bx = innerLeft + (int) ((road.toX() - minX) * scale);
            int bz = innerTop + (int) ((road.toZ() - minZ) * scale);
            int steps = Math.max(1, Math.max(Math.abs(bx - ax), Math.abs(bz - az)) / 3);
            for (int i = 0; i <= steps; i++) {
                int x = ax + (bx - ax) * i / steps;
                int y = az + (bz - az) * i / steps;
                g.fill(x, y, x + 1, y + 1, Ui.ON_WELL_DIM);
            }
        }
        for (TerrainMapView.Cell cell : map.cells()) {
            int x = innerLeft + (int) ((cell.x() - minX) * scale);
            int y = innerTop + (int) ((cell.z() - minZ) * scale);
            int color = cell.water() ? Ui.ACCENT : cell.fresh() ? Ui.GOOD : Ui.ON_WELL_DIM;
            g.fill(x, y, x + DOT, y + DOT, color);
        }
        for (TerrainMapView.Site site : map.sites()) {
            int x = innerLeft + (int) ((site.x() - minX) * scale);
            int y = innerTop + (int) ((site.z() - minZ) * scale);
            g.fill(x - 1, y - 1, x + DOT + 1, y + DOT + 1, Ui.WARN);
        }
        Ui.text(
                g,
                font,
                Component.translatable(
                        "friendai.menu.map.count",
                        map.cells().size(),
                        map.sites().size(),
                        map.roads().size()),
                innerLeft,
                top + height - PAD - font.lineHeight,
                Ui.ON_WELL_DIM);
    }
}
