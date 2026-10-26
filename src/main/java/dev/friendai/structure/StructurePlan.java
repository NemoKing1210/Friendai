package dev.friendai.structure;

import java.util.List;

/** The planner's answer for one structure at one site. */
public sealed interface StructurePlan {
    enum Reason { PROTECTED, NO_ROOM, ENTITY_IN_WAY, WRONG_DIMENSION, MISSING_ITEM, LIMIT_REACHED }

    /** One line for the LLM, in English. */
    String describe();

    record Ready(String structureId, List<StructureStep> steps) implements StructurePlan {
        public Ready {
            steps = List.copyOf(steps);
        }

        @Override
        public String describe() {
            StringBuilder out = new StringBuilder(structureId).append(": ");
            for (int i = 0; i < steps.size(); i++) {
                if (i > 0) {
                    out.append(", then ");
                }
                out.append(switch (steps.get(i)) {
                    case StructureStep.Clear c -> "clear the site";
                    case StructureStep.BuildBody b -> "build " + b.missing().size() + " body blocks";
                    case StructureStep.Finish f -> "finish it";
                    case StructureStep.Verify v -> "check it worked";
                });
            }
            return out.toString();
        }
    }

    record Blocked(Reason reason, String detail) implements StructurePlan {
        @Override
        public String describe() {
            return "cannot build (" + reason.name().toLowerCase() + "): " + detail;
        }
    }

    record Done(String structureId) implements StructurePlan {
        @Override
        public String describe() {
            return structureId + " is already finished";
        }
    }
}
