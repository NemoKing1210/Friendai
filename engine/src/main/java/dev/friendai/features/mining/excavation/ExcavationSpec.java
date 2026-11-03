package dev.friendai.features.mining.excavation;

import java.util.Objects;
import java.util.Optional;

/** A validated excavation request: a bounded region, a cap and an optional exact item goal. */
public record ExcavationSpec(
        ExcavationGeometry geometry,
        int count,
        String untilItem,
        int untilCount,
        boolean collect,
        boolean containers,
        boolean allowLiquids,
        boolean requireTool,
        int reserveDurability) {
    public static final int MAX_RESERVE = 95;

    public ExcavationSpec {
        Objects.requireNonNull(geometry, "geometry");
        if (count < 1 || count > ExcavationGeometry.MAX_VOLUME) {
            throw new IllegalArgumentException("count must be 1.." + ExcavationGeometry.MAX_VOLUME);
        }
        boolean hasItem = untilItem != null && !untilItem.isBlank();
        if (hasItem != (untilCount > 0)) {
            throw new IllegalArgumentException("until_item and until_count must be given together");
        }
        if (hasItem && !collect) {
            throw new IllegalArgumentException("until_item needs collect:true");
        }
        if (untilCount < 0 || untilCount > ExcavationGeometry.MAX_VOLUME) {
            throw new IllegalArgumentException("until_count must be 1.." + ExcavationGeometry.MAX_VOLUME);
        }
        if (reserveDurability < 0 || reserveDurability > MAX_RESERVE) {
            throw new IllegalArgumentException("reserve_durability must be 0.." + MAX_RESERVE);
        }
    }

    public static Optional<ExcavationSpec> of(
            ExcavationGeometry geometry,
            int count,
            String untilItem,
            int untilCount,
            boolean collect,
            boolean containers,
            boolean allowLiquids,
            boolean requireTool,
            int reserveDurability) {
        if (geometry == null) {
            return Optional.empty();
        }
        int effective = count <= 0 ? geometry.volume() : Math.min(count, geometry.volume());
        try {
            return Optional.of(new ExcavationSpec(
                    geometry,
                    effective,
                    untilItem,
                    untilCount,
                    collect,
                    containers,
                    allowLiquids,
                    requireTool,
                    reserveDurability));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    public boolean hasUntil() {
        return untilItem != null && !untilItem.isBlank() && untilCount > 0;
    }

    public ExcavationGoal goal() {
        return new ExcavationGoal(count, untilItem, untilCount);
    }
}
