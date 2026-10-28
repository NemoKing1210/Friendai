package dev.friendai.features.navigation.pathing;

/** The kind of action one path step performs. */
public enum Move {
    /** Along the ground to an orthogonal neighbour at the same height. */
    WALK,
    /** Along the ground to a diagonal neighbour at the same height. */
    WALK_DIAGONAL,
    /** One block up onto a neighbour. */
    ASCEND,
    /** One to three blocks down, or any height into water. */
    DESCEND,
    /** Over a one-block gap to the cell two away. */
    JUMP_GAP,
    /** Through water. */
    SWIM,
    /** Up or down a ladder, vine or scaffolding. */
    CLIMB,
    /** Through a closed wooden door or gate. */
    DOOR,
    /** Break the blocking cells of a neighbour, then walk in. */
    DIG,
    /** Break the cells above, then step up. */
    DIG_ASCEND,
    /** Break the neighbour's cells and the cell below its foot, then step down. */
    DIG_DESCEND,
    /** Jump and place a block under itself. */
    PILLAR,
    /** Place a block in the support cell of a neighbour, then walk onto it. */
    BRIDGE
}
