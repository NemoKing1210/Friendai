package dev.friendai.features.farming;

/**
 * One classified cell, relative to the farm centre. {@code crop} is the block id of a RIPE or GROWING cell (else
 * null); {@code seed} is the item that replants a RIPE cell (null for fruit, cane, berries); {@code soil} is set
 * for EMPTY_SOIL (else null).
 */
public record FieldCell(int dx, int dy, int dz, CellKind kind, String crop, String seed, int age, Soil soil) {
    public FieldCell {
        if (kind == null) {
            throw new IllegalArgumentException("kind must not be null");
        }
        if ((kind == CellKind.RIPE || kind == CellKind.GROWING) && crop == null) {
            throw new IllegalArgumentException(kind + " cells need a crop id");
        }
        if (kind == CellKind.EMPTY_SOIL && soil == null) {
            throw new IllegalArgumentException("EMPTY_SOIL cells need a soil");
        }
        if (age < 0) {
            throw new IllegalArgumentException("age must not be negative: " + age);
        }
    }
}
