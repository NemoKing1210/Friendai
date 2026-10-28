package dev.friendai.features.construction.special;

import java.util.Set;

/** One action of a structure plan, executed in order. */
public sealed interface StructureStep {
    /** Remove replaceable blocks (grass, snow layers) from the footprint, keeping correct body blocks. */
    record Clear() implements StructureStep {}

    /** Place the body blocks at these offsets. */
    record BuildBody(Set<Offset> missing) implements StructureStep {
        public BuildBody {
            missing = Set.copyOf(missing);
        }
    }

    record Finish(Trigger trigger) implements StructureStep {}

    record Verify(Outcome outcome) implements StructureStep {}
}
