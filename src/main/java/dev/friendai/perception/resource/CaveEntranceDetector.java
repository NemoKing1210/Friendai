package dev.friendai.perception.resource;

import dev.friendai.features.navigation.pathing.Pos;
import java.util.List;
import java.util.Optional;

/** Classifies only an unobstructed sight ray that descends through a visible opening. */
public final class CaveEntranceDetector {
    private static final int MINIMUM_DESCENT = 3;
    private static final int MINIMUM_INTERIOR_CELLS = 3;

    public record Entrance(Pos approach, Pos opening, Pos interior) {}

    private CaveEntranceDetector() {}

    public static Optional<Entrance> detect(List<Pos> openCells, Pos eyeCell, Pos visibleWall) {
        if (openCells == null
                || eyeCell == null
                || visibleWall == null
                || visibleWall.y() > eyeCell.y() - MINIMUM_DESCENT
                || openCells.size() < MINIMUM_INTERIOR_CELLS + 1) {
            return Optional.empty();
        }
        int openingIndex = -1;
        int interiorCells = 0;
        int lastInteriorIndex = -1;
        for (int index = 0; index < openCells.size(); index++) {
            Pos cell = openCells.get(index);
            if (cell == null) return Optional.empty();
            if (cell.y() <= eyeCell.y() - 2) {
                if (openingIndex < 0) openingIndex = index;
                interiorCells++;
                lastInteriorIndex = index;
            }
        }
        if (openingIndex < 1 || interiorCells < MINIMUM_INTERIOR_CELLS || lastInteriorIndex != openCells.size() - 1)
            return Optional.empty();
        Pos approach = openCells.get(openingIndex - 1);
        Pos opening = openCells.get(openingIndex);
        Pos interior = openCells.get(lastInteriorIndex);
        return Optional.of(new Entrance(approach, opening, interior));
    }
}
