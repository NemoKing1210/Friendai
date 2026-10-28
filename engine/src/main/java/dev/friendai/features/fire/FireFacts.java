package dev.friendai.features.fire;

import dev.friendai.features.navigation.pathing.Pos;

/**
 * The world as one decision needs it: what the bag holds, what the weather and the dimension allow, and where a
 * target is. Every position is decided by the adapter, so the planner stays pure.
 *
 * @param nether water cannot be placed in this dimension at all
 * @param raining rain is already putting the fire out, so nothing has to be spent
 * @param self where the companion stands
 * @param waterBuckets water buckets in the bag
 * @param powderSnowBuckets powder snow buckets in the bag
 * @param waterSource standing water the companion can step into, or null
 * @param waterCell a cell where a bucket may safely be poured for the target, or null when none exists
 * @param targetInReach the owner or the burning mob is close enough to help
 */
public record FireFacts(
        boolean nether,
        boolean raining,
        Pos self,
        int waterBuckets,
        int powderSnowBuckets,
        Pos waterSource,
        Pos waterCell,
        boolean targetInReach) {}
