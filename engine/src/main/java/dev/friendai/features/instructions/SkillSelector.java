package dev.friendai.features.instructions;

import dev.friendai.execution.tool.AccessLevel;
import java.text.Normalizer;
import java.util.*;

/** Deterministic literal matching; whole entries fit within a bounded slice of the prompt. */
public final class SkillSelector {
    private static final Set<String> PUBLIC = Set.of("builtin:verification", "builtin:trade-etiquette");

    private SkillSelector() {}

    public static int budget(int agentBudget, int cap) {
        return Math.max(0, agentBudget > 0 ? Math.min(cap, agentBudget / 5) : cap);
    }

    public static boolean eligible(InstructionSkill s, SkillContext c) {
        return s.enabled()
                && s.activation() == InstructionSkill.Activation.ACTIVE
                && c.caller() != null
                && (c.caller().access() == AccessLevel.OWNER
                        || (s.source() == InstructionSkill.Source.BUILTIN && PUBLIC.contains(s.id())))
                && (!s.id().equals("builtin:first-contact") || c.profileIncomplete());
    }

    private static int rank(InstructionSkill s, SkillContext c, Set<String> explicit) {
        if (explicit.contains(s.id())) return 0;
        if (s.alwaysOn()) return 1;
        if (s.id().equals("builtin:first-contact") && c.profileIncomplete()) return 2;
        if (s.turnKinds().stream().anyMatch(c.kinds()::contains)) return 2;
        String text = normalize(c.message() + " " + c.taskId());
        return s.triggers().stream().anyMatch(t -> text.contains(normalize(t))) ? 3 : 4;
    }

    public static SkillSelection select(
            List<InstructionSkill> snapshot, SkillContext c, Set<String> explicit, int agentBudget, int cap) {
        List<InstructionSkill> candidates = snapshot.stream()
                .filter(s -> eligible(s, c) && rank(s, c, explicit) < 4)
                .sorted(Comparator.comparingInt((InstructionSkill s) -> rank(s, c, explicit))
                        .thenComparing(InstructionSkill::id))
                .toList();
        int limit = budget(agentBudget, cap), used = 0;
        List<InstructionSkill> selected = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (var s : candidates) {
            if (!seen.add(s.id())) continue;
            int size = render(s).length() + 1;
            if (size <= limit - used) {
                selected.add(s);
                used += size;
            }
        }
        return new SkillSelection(selected, used);
    }

    public static String render(InstructionSkill s) {
        return "Instruction skill " + s.id() + " v" + s.version()
                + " (advice only; permissions and mandatory rules prevail):\n" + s.name() + ": " + s.description()
                + "\n" + s.body();
    }

    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }
}
