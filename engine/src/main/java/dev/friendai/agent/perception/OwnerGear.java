package dev.friendai.agent.perception;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * What the speaking player holds and wears, in plain item names with no Minecraft types. An empty string means an
 * empty hand; only worn armor pieces appear in {@link #armor()}, so a slot nobody filled is an absent entry.
 * {@link #holding()} and {@link #wearing()} render the two prompt lines, and {@link #NOTHING} is a player holding
 * nothing and wearing no armor.
 */
public record OwnerGear(String mainHand, String offHand, List<Piece> armor) {

    /** The four armor slots, in the order the prompt lists them. */
    public enum Slot {
        HEAD("head"),
        CHEST("chest"),
        LEGS("legs"),
        FEET("feet");

        private final String label;

        Slot(String label) {
            this.label = label;
        }

        /** The word the prompt uses for this slot. */
        public String label() {
            return label;
        }
    }

    /** One worn armor piece: the slot and the item as the prompt names it. */
    public record Piece(Slot slot, String item) {}

    /** A player holding nothing and wearing no armor. */
    public static final OwnerGear NOTHING = new OwnerGear("", "", List.of());

    public OwnerGear {
        armor = armor.stream().sorted(Comparator.comparing(Piece::slot)).toList();
    }

    /** The {@code holding:} value: the main hand, followed by the off hand when it holds something. */
    public String holding() {
        if (mainHand.isEmpty()) {
            return offHand.isEmpty() ? "nothing" : "nothing in the main hand, offhand " + offHand;
        }
        return offHand.isEmpty() ? mainHand : mainHand + ", offhand " + offHand;
    }

    /** The {@code wearing:} value: worn pieces in slot order, or "nothing". */
    public String wearing() {
        return armor.isEmpty()
                ? "nothing"
                : armor.stream()
                        .map(piece -> piece.slot().label() + " " + piece.item())
                        .collect(Collectors.joining(", "));
    }
}
