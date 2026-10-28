package dev.friendai.structure;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Turns a structure and a world snapshot into ordered steps, or the reason it cannot be done. */
public final class StructurePlanner {
    private StructurePlanner() {}

    /** Checks run in this order and the first match wins. */
    public static StructurePlan plan(SpecialStructure structure, StructureSnapshot snap) {
        String id = structure.id();
        if (snap.outcomeAlreadyPresent()) {
            return new StructurePlan.Done(id);
        }
        if (snap.protectedArea()) {
            return blocked(StructurePlan.Reason.PROTECTED, id + " site is protected");
        }
        if (snap.inEnd() && !structure.allowedInEnd()) {
            return blocked(StructurePlan.Reason.WRONG_DIMENSION, id + " does not work in the End");
        }
        if (structure.outcome() instanceof Outcome.EntitySpawned spawned
                && snap.nearbySpawned() >= snap.maxNearbySpawned()) {
            return blocked(
                    StructurePlan.Reason.LIMIT_REACHED,
                    "already " + snap.nearbySpawned() + " " + spawned.entityType() + " nearby (limit "
                            + snap.maxNearbySpawned() + ")");
        }
        if (snap.entityInFootprint()) {
            String who = snap.blockers().isBlank() ? "someone" : snap.blockers();
            return blocked(StructurePlan.Reason.ENTITY_IN_WAY, who + " stands where " + id + " goes");
        }
        for (String item : structure.requiredItems()) {
            if (snap.bag().getOrDefault(item, 0) < 1) {
                return blocked(StructurePlan.Reason.MISSING_ITEM, "need " + item);
            }
        }
        if (!snap.footprintFree()) {
            return blocked(StructurePlan.Reason.NO_ROOM, "no room for " + id);
        }

        Set<Offset> missing = new HashSet<>();
        for (var block : structure.body().blocks()) {
            Offset at = new Offset(block.x(), block.y(), block.z());
            if (!snap.correctBodyBlocks().contains(at)) {
                missing.add(at);
            }
        }
        List<StructureStep> steps = new ArrayList<>();
        if (!missing.isEmpty()) {
            steps.add(new StructureStep.Clear());
            steps.add(new StructureStep.BuildBody(missing));
        }
        steps.add(new StructureStep.Finish(structure.trigger()));
        steps.add(new StructureStep.Verify(structure.outcome()));
        return new StructurePlan.Ready(id, steps);
    }

    private static StructurePlan blocked(StructurePlan.Reason reason, String detail) {
        return new StructurePlan.Blocked(reason, detail);
    }
}
