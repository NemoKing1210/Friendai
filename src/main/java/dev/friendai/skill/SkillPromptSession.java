package dev.friendai.skill;

import dev.friendai.agent.prompt.PromptSection;
import dev.friendai.brain.Persona;
import dev.friendai.tool.*;
import java.util.*;

/** One immutable book snapshot with bounded explicit tool activations. */
public final class SkillPromptSession {
    public static final SkillPromptSession NONE = new SkillPromptSession(List.of(), null, 0, 0);
    private final List<InstructionSkill> snapshot;
    private final SkillContext context;
    private final int agentBudget, cap;
    private final Set<String> delivered = new HashSet<>();
    private int deliveredChars;
    private Set<String> actualActive = Set.of();
    private boolean explicitProfile;

    public void recordAssembly(List<String> keptSectionIds) {
        actualActive = Set.copyOf(keptSectionIds);
    }

    public SkillPromptSession(List<InstructionSkill> snapshot, SkillContext context, int agentBudget, int cap) {
        this.snapshot = List.copyOf(snapshot);
        this.context = context;
        this.agentBudget = agentBudget;
        this.cap = cap;
    }

    public List<PromptSection> sections() {
        if (context == null) return List.of();
        int remaining = SkillSelector.budget(agentBudget, cap) - deliveredChars;
        SkillSelection selection = SkillSelector.select(
                snapshot.stream().filter(s -> !delivered.contains(s.id())).toList(), context, Set.of(), 0, remaining);
        List<PromptSection> out = new ArrayList<>();
        int priority = 30;
        for (var s : selection.skills())
            out.add(new SkillPromptSection("skill:" + s.id(), SkillSelector.render(s), priority--));
        String details = context.description().details();
        boolean include = context.caller().access() == AccessLevel.OWNER
                && (SkillSelector.normalize(context.message()).contains("profile")
                        || explicitProfile
                        || selection.skills().stream().anyMatch(InstructionSkill::includeProfile));
        String address = context.caller().access() == AccessLevel.OWNER
                ? context.ownerProfile().address()
                : "";
        String profile = (address.isBlank() ? "" : "Owner preferred address: " + address + "\n")
                + (include && !details.isBlank() ? "Description details:\n" + details : "");
        if (!profile.isBlank() && profile.length() + 1 <= remaining - selection.renderedChars())
            out.add(new SkillPromptSection("skill:profile", profile, 10));
        return List.copyOf(out);
    }

    public ToolResult request(String id) {
        if (context == null || context.caller().access() != AccessLevel.OWNER)
            return ToolResult.error("Skill access denied.");
        var found = snapshot.stream()
                .filter(s -> s.id().equals(id) && SkillSelector.eligible(s, context))
                .findFirst();
        if (found.isEmpty()) return ToolResult.error("Skill is unavailable.");
        if (delivered.contains(id) || actualActive.contains("skill:" + id))
            return ToolResult.ok("Skill is already active this turn.");
        String text = SkillSelector.render(found.get());
        if (text.length() + 1 > SkillSelector.budget(agentBudget, cap) - deliveredChars)
            return ToolResult.error("Skill does not fit this turn's instruction budget.");
        delivered.add(id);
        deliveredChars += text.length() + 1;
        explicitProfile |= found.get().includeProfile();
        return ToolResult.ok(text);
    }

    public List<String> versionedIds(List<String> sectionIds) {
        return sectionIds.stream()
                .map(id -> snapshot.stream()
                        .filter(s -> id.equals("skill:" + s.id()))
                        .findFirst()
                        .map(s -> id + "@v" + s.version())
                        .orElse(id))
                .toList();
    }

    public Persona persona(Persona base) {
        if (context == null || context.caller().access() != AccessLevel.OWNER) return base;
        String language = base.language();
        if ((language == null || language.isBlank() || "auto".equalsIgnoreCase(language))
                && !"auto".equals(context.ownerProfile().language()))
            language = context.ownerProfile().language();
        return new Persona(base.name(), base.description(), language, base.ownerLanguage());
    }

    public String ownerAddress() {
        return context != null && context.caller().access() == AccessLevel.OWNER
                ? context.ownerProfile().address()
                : "";
    }
}
