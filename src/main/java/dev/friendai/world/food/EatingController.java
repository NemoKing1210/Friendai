package dev.friendai.world.food;

/** The live eating arbitration a body shares with its combat controller. */
public interface EatingController {
    boolean eatFromSlot(int slot);

    boolean isEating();

    /** Whether combat is using the hands, so another eater must wait. */
    boolean busy();

    void stopEating();
}
