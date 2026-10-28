package dev.friendai.features.fire;

import dev.friendai.features.navigation.pathing.Pos;

/** One thing the companion has to do to put a fire out; the executor runs them in order. */
public sealed interface ExtinguishStep {

    /** Pour a water bucket at {@code pos}; the empty bucket comes back. */
    record PlaceWater(Pos pos) implements ExtinguishStep {}

    /** Pour water on the campfire at {@code pos}, which flips its lit state. */
    record DouseCampfire(Pos pos) implements ExtinguishStep {}

    /** Take the source back with an empty bucket, so no water is left behind. */
    record RecoverBucket(Pos pos) implements ExtinguishStep {}

    /** Walk into water that is already there; standing in it clears the fire. */
    record EnterWater(Pos pos) implements ExtinguishStep {}

    /** Place powder snow at {@code pos} and stand in it. */
    record StandInPowderSnow(Pos pos) implements ExtinguishStep {}

    /** Break the fire block by hand; it drops nothing and vanilla allows it. */
    record BreakFire(Pos pos) implements ExtinguishStep {}

    /** Stop, naming the rail that blocked the plan. */
    record GiveUp(String reason) implements ExtinguishStep {}
}
