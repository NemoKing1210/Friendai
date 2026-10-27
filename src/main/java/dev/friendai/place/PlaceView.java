package dev.friendai.place;

import dev.friendai.memory.MenuStatus;
import java.util.List;
import java.util.Optional;

/**
 * What the places tab shows: whether places are enabled, one row per remembered place and the last action's
 * result. {@code kindKey} and {@code sourceKey} are translation keys, never enum names, so the client stays free
 * of server-side wording.
 */
public record PlaceView(boolean enabled, List<Row> places, Optional<MenuStatus> status) {

    /**
     * One place as the tab lists it. {@code lastVisitTick} is the game time of the last visit and stays {@code 0}
     * for a place never visited; {@code label} and {@code note} are empty when the owner gave none.
     */
    public record Row(
            long id,
            String name,
            String kindKey,
            String label,
            String dimension,
            int x,
            int y,
            int z,
            int radius,
            int visits,
            long lastVisitTick,
            String sourceKey,
            String note) {}
}
