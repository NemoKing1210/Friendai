package dev.friendai.world.food;

/** A reflex that may hold the hands for a few ticks, so another one does not act on the same item. */
public interface BodyUse {
    boolean busy();
}
