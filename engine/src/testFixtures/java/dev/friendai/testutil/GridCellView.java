package dev.friendai.testutil;

import dev.friendai.features.navigation.pathing.Cell;
import dev.friendai.features.navigation.pathing.CellView;
import dev.friendai.features.navigation.pathing.Pos;
import java.util.HashMap;
import java.util.Map;

/**
 * A world spelled out as ASCII layers for the pathing unit tests.
 *
 * <p>Layers are given bottom-up (`y` = index); each layer holds rows separated by `/` (`z`), one character per
 * `x`. See {@link #cellFor(char)} for the characters.
 */
public final class GridCellView implements CellView {

    private final Map<Long, Cell> cells = new HashMap<>();
    private final Map<Long, Cell> overrides = new HashMap<>();
    private final int width;
    private final int height;
    private final int depth;

    public GridCellView(String... layers) {
        height = layers.length;
        int w = 0;
        int d = 0;
        for (String layer : layers) {
            String[] rows = layer.split("/", -1);
            d = Math.max(d, rows.length);
            for (String row : rows) {
                w = Math.max(w, row.length());
            }
        }
        width = w;
        depth = d;
        for (int y = 0; y < layers.length; y++) {
            String[] rows = layers[y].split("/", -1);
            for (int z = 0; z < rows.length; z++) {
                String row = rows[z];
                for (int x = 0; x < row.length(); x++) {
                    cells.put(pack(x, y, z), cellFor(row.charAt(x)));
                }
            }
        }
    }

    static Cell cellFor(char c) {
        return switch (c) {
            case '.' -> Cell.AIR;
            case ',' -> new Cell(Cell.Kind.PASSABLE, -1, false, false);
            case '#' -> new Cell(Cell.Kind.SOLID, 30, false, false);
            case 'd' -> new Cell(Cell.Kind.SOLID, 8, false, false);
            case 'l' -> new Cell(Cell.Kind.SOLID, 60, false, false);
            case 'p' -> new Cell(Cell.Kind.SOLID, -1, false, false);
            case 'b' -> new Cell(Cell.Kind.SOLID, -1, false, false);
            case 'g' -> new Cell(Cell.Kind.SOLID, 9, true, false);
            case 'G' -> new Cell(Cell.Kind.SOLID, -1, false, false);
            case '_' -> new Cell(Cell.Kind.PARTIAL, 30, false, false);
            case 'F' -> new Cell(Cell.Kind.TALL, -1, false, false);
            case '~' -> new Cell(Cell.Kind.WATER, -1, false, true);
            case 'L' -> new Cell(Cell.Kind.LAVA, -1, false, false);
            case 'H' -> new Cell(Cell.Kind.CLIMBABLE, 5, false, false);
            case 'D' -> new Cell(Cell.Kind.DOOR, 15, false, false);
            case '^' -> new Cell(Cell.Kind.HAZARD, -1, false, false);
            case '?' -> Cell.UNLOADED;
            default -> Cell.UNLOADED;
        };
    }

    @Override
    public Cell at(int x, int y, int z) {
        long key = pack(x, y, z);
        Cell override = overrides.get(key);
        if (override != null) {
            return override;
        }
        if (x < 0 || y < 0 || z < 0 || x >= width || y >= height || z >= depth) {
            return Cell.UNLOADED;
        }
        Cell cell = cells.get(key);
        return cell == null ? Cell.UNLOADED : cell;
    }

    /** Makes a cell unbreakable and unplaceable, as a protected or blacklisted block reads. Mutates and returns this. */
    public GridCellView protect(Pos p) {
        Cell cell = at(p.x(), p.y(), p.z());
        overrides.put(pack(p.x(), p.y(), p.z()), new Cell(cell.kind(), -1, cell.falls(), false));
        return this;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int depth() {
        return depth;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }
}
