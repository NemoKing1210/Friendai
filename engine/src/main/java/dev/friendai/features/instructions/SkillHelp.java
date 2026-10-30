package dev.friendai.features.instructions;

import java.util.*;
import java.util.stream.Collectors;

/** The authoring checklist from the shipped procedure plus limits generated from constants and config. */
public final class SkillHelp {
    private SkillHelp() {}

    public static String render(
            String authoringBody, int writesPerHour, int writesPerOutcome, boolean requireApproval) {
        String body = authoringBody == null ? "" : authoringBody.strip();
        if (body.isEmpty()) return "The authoring checklist is unavailable.";
        return "Instruction skill authoring checklist.\n"
                + body
                + "\n\nHard limits: body at most "
                + SkillLimits.BODY
                + " characters, name at most "
                + SkillLimits.NAME
                + ", description at most "
                + SkillLimits.DESCRIPTION
                + ", at most "
                + SkillLimits.TERMS
                + " triggers and "
                + SkillLimits.TERMS
                + " tags of at most "
                + SkillLimits.TERM
                + " characters each. Plain text only: no code fences, links, addresses, shell commands or"
                + " credentials.\n"
                + "turnKinds: "
                + Arrays.stream(InstructionSkill.Kind.values())
                        .map(kind -> kind.name().toLowerCase(Locale.ROOT))
                        .collect(Collectors.joining(", "))
                + ".\n"
                + "manage_skill actions: create, update (send the current expectedVersion), disable, delete. It is"
                + " offered only after a finished goal or a failed shift.\n"
                + writeAllowance(writesPerHour, writesPerOutcome)
                + (requireApproval
                        ? "A new procedure stays pending until the owner approves it.\n"
                        : "A new procedure is active at once.\n")
                + "Facts belong in remember; procedures belong in manage_skill. Call list_skills first so you"
                + " update an existing id instead of duplicating it.\n";
    }

    private static String writeAllowance(int writesPerHour, int writesPerOutcome) {
        if (writesPerHour <= 0 || writesPerOutcome <= 0) return "Teaching writes are disabled by configuration.\n";
        return "Allowance: at most " + writesPerHour + " writes per hour and " + writesPerOutcome + " per outcome.\n";
    }
}
