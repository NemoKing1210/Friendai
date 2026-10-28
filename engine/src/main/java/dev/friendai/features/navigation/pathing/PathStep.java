package dev.friendai.features.navigation.pathing;

import java.util.List;

/**
 * One edge of a planned path. {@code breaks} are the cells to break, in order; {@code places} the cells to fill, in
 * order. {@code headUnderwater} counts consecutive steps with the head in water, this one included.
 */
public record PathStep(
        Node from, Node to, Move move, List<Pos> breaks, List<Pos> places, double cost, int headUnderwater) {

    public PathStep {
        breaks = List.copyOf(breaks);
        places = List.copyOf(places);
    }
}
