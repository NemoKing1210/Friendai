package dev.friendai.fire;

import dev.friendai.pathing.Pos;
import java.util.ArrayList;
import java.util.List;

/**
 * The fire decision, without the world: facts and judged cells in, an ordered {@link ExtinguishPlan} out.
 *
 * <p>Order, per burning cell: water first (a bucket, then it comes back), then breaking a fire by hand, then a
 * named refusal. A campfire is never broken — it is a cooking station and it drops its contents — so without
 * water it is left lit. Rails are absolute: no water in the nether, no break inside a portal frame, nothing on a
 * reserved or protected cell.
 */
public final class FirePlanner {

    /** Water cannot be placed in the nether. */
    static final String NETHER = "the nether has no water";
    /** The bag has nothing that puts a fire out. */
    static final String NO_MEANS = "nothing to put it out with";
    /** No bucket for a target that is not the companion itself. */
    static final String NO_WATER_FOR_THEM = "no water for them";
    /** The owner or the burning mob cannot be helped from here. */
    static final String OUT_OF_REACH_FOR_THEM = "they are out of reach";
    /** A cell the adapter could not find among the burning ones. */
    static final String ALREADY_OUT = "it is already out";
    /** The companion's own cooking campfire or the one it smokes a hive with. */
    static final String COOKING_STATION = "it is my cooking station";
    /** The level or a place refuses the action. */
    static final String PROTECTED = "it is protected";
    /** The companion cannot get to the cell. */
    static final String OUT_OF_REACH = "it is out of reach";
    /** Breaking it would disturb a nether portal frame. */
    static final String PORTAL_FRAME = "it is inside a portal frame";
    /** A campfire without water: it must not be broken. */
    static final String CAMPFIRE_STAYS_LIT = "a campfire stays lit without water";
    /** The break list forbids it and water is not an option. */
    static final String CANNOT_BREAK = "I cannot break it";

    private FirePlanner() {}

    public static ExtinguishPlan plan(FireTarget target, FireFacts facts, List<BurningCell> cells) {
        List<ExtinguishStep> steps = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        switch (target) {
            case FireTarget.Self self -> planSelf(facts, steps, notes);
            case FireTarget.Owner owner -> planOther(facts, steps, notes);
            case FireTarget.BurningEntity entity -> planOther(facts, steps, notes);
            case FireTarget.Block block -> planBlock(block.pos(), facts, cells, steps, notes);
            case FireTarget.Area area -> {
                for (Pos pos : area.cells()) {
                    planBlock(pos, facts, cells, steps, notes);
                    if (!steps.isEmpty() && steps.getLast() instanceof ExtinguishStep.GiveUp) {
                        break;
                    }
                }
            }
        }
        return new ExtinguishPlan(target, steps, notes);
    }

    private static void planSelf(FireFacts facts, List<ExtinguishStep> steps, List<String> notes) {
        if (facts.raining()) {
            notes.add("it is raining and the fire is going out on its own");
            return;
        }
        if (facts.waterSource() != null) {
            steps.add(new ExtinguishStep.EnterWater(facts.waterSource()));
            return;
        }
        if (facts.powderSnowBuckets() > 0) {
            steps.add(new ExtinguishStep.StandInPowderSnow(facts.self()));
            return;
        }
        if (facts.waterBuckets() > 0 && facts.waterCell() != null) {
            if (facts.nether()) {
                steps.add(new ExtinguishStep.GiveUp(NETHER));
                return;
            }
            steps.add(new ExtinguishStep.PlaceWater(facts.waterCell()));
            steps.add(new ExtinguishStep.RecoverBucket(facts.waterCell()));
            return;
        }
        steps.add(new ExtinguishStep.GiveUp(facts.nether() ? NETHER : NO_MEANS));
    }

    private static void planOther(FireFacts facts, List<ExtinguishStep> steps, List<String> notes) {
        if (!facts.targetInReach()) {
            steps.add(new ExtinguishStep.GiveUp(OUT_OF_REACH_FOR_THEM));
            return;
        }
        if (facts.raining()) {
            notes.add("it is raining and the fire is going out on its own");
            return;
        }
        if (facts.nether()) {
            steps.add(new ExtinguishStep.GiveUp(NETHER));
            return;
        }
        if (facts.waterBuckets() <= 0 || facts.waterCell() == null) {
            steps.add(new ExtinguishStep.GiveUp(NO_WATER_FOR_THEM));
            return;
        }
        steps.add(new ExtinguishStep.PlaceWater(facts.waterCell()));
        steps.add(new ExtinguishStep.RecoverBucket(facts.waterCell()));
    }

    private static void planBlock(Pos pos, FireFacts facts, List<BurningCell> cells,
            List<ExtinguishStep> steps, List<String> notes) {
        BurningCell cell = find(cells, pos);
        if (cell == null) {
            steps.add(new ExtinguishStep.GiveUp(ALREADY_OUT));
            return;
        }
        BurningCell.Rails rails = cell.rails();
        if (rails.reserved()) {
            steps.add(new ExtinguishStep.GiveUp(COOKING_STATION));
            return;
        }
        if (rails.protectedCell()) {
            steps.add(new ExtinguishStep.GiveUp(PROTECTED));
            return;
        }
        if (!rails.reachable()) {
            steps.add(new ExtinguishStep.GiveUp(OUT_OF_REACH));
            return;
        }
        boolean campfire = cell.kind() == BurningCell.Kind.LIT_CAMPFIRE;
        boolean water = facts.waterBuckets() > 0 && !facts.nether() && rails.waterSafe();
        if (water) {
            steps.add(campfire
                    ? new ExtinguishStep.DouseCampfire(pos)
                    : new ExtinguishStep.PlaceWater(pos));
            steps.add(new ExtinguishStep.RecoverBucket(pos));
            return;
        }
        if (campfire) {
            steps.add(new ExtinguishStep.GiveUp(facts.nether() ? NETHER : CAMPFIRE_STAYS_LIT));
            return;
        }
        if (rails.inPortalFrame()) {
            steps.add(new ExtinguishStep.GiveUp(PORTAL_FRAME));
            return;
        }
        if (!rails.breakable()) {
            steps.add(new ExtinguishStep.GiveUp(CANNOT_BREAK));
            return;
        }
        steps.add(new ExtinguishStep.BreakFire(pos));
    }

    private static BurningCell find(List<BurningCell> cells, Pos pos) {
        for (BurningCell cell : cells) {
            if (cell.pos().equals(pos)) {
                return cell;
            }
        }
        return null;
    }
}
