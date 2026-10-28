package dev.friendai.features.instructions;

import java.util.*;
import java.util.function.UnaryOperator;

/** One server-thread-owned instruction collection. Every mutation is revisioned. */
public final class SkillBook {
    final TreeMap<String, InstructionSkill> records = new TreeMap<>();
    final TreeSet<String> disabled = new TreeSet<>();
    final List<Long> writes = new ArrayList<>();
    final LinkedHashMap<String, Integer> outcomes = new LinkedHashMap<>();
    long clockHighWater;
    long revision;
    private SkillLimits limits;

    public void setLimits(SkillLimits value) {
        limits = java.util.Objects.requireNonNull(value);
    }

    private final UnaryOperator<String> redact;
    private final InstructionSkill.Scope scope;

    public SkillBook(SkillLimits limits, UnaryOperator<String> redact, InstructionSkill.Scope scope) {
        this.limits = limits;
        this.redact = redact;
        this.scope = scope;
    }

    public long revision() {
        return revision;
    }

    public List<InstructionSkill> snapshot() {
        return List.copyOf(records.values());
    }

    public Set<String> disabledIds() {
        return Set.copyOf(disabled);
    }

    public Optional<InstructionSkill> find(String id) {
        return Optional.ofNullable(records.get(id));
    }

    public InstructionSkill create(
            SkillDraft input, InstructionSkill.Source source, UUID author, long now, boolean pending) {
        SkillDraft draft = SkillText.validate(input, redact);
        if (source == InstructionSkill.Source.BUILTIN)
            throw new IllegalArgumentException("Builtin resources are immutable.");
        int count = scope == InstructionSkill.Scope.SERVER ? limits.serverCount() : limits.ownerCount();
        if (records.size() >= count) throw new IllegalArgumentException("Skill count limit reached.");
        String prefix = scope == InstructionSkill.Scope.SERVER
                ? "server:"
                : source == InstructionSkill.Source.MODEL ? "model:" : "owner:";
        InstructionSkill skill = value(
                prefix + UUID.randomUUID(),
                draft,
                source,
                author,
                now,
                now,
                1,
                pending ? InstructionSkill.Activation.PENDING : InstructionSkill.Activation.ACTIVE);
        putChecked(skill);
        return skill;
    }

    public InstructionSkill edit(String id, int expected, SkillDraft input, long now) {
        InstructionSkill old = checked(id, expected);
        InstructionSkill next = value(
                id,
                SkillText.validate(input, redact),
                old.source(),
                old.author(),
                old.createdAt(),
                Math.max(old.updatedAt(), now),
                Math.addExact(old.version(), 1),
                old.activation());
        putChecked(next);
        return next;
    }

    public void delete(String id, int expected) {
        checked(id, expected);
        records.remove(id);
        revision++;
    }

    public void approve(String id, int expected) {
        InstructionSkill old = checked(id, expected);
        putChecked(value(
                id,
                old.draft(),
                old.source(),
                old.author(),
                old.createdAt(),
                old.updatedAt(),
                Math.addExact(old.version(), 1),
                InstructionSkill.Activation.ACTIVE));
    }

    public void override(String id, boolean enabled) {
        if (enabled ? disabled.remove(id) : disabled.add(id)) revision++;
    }

    private InstructionSkill checked(String id, int expected) {
        InstructionSkill old = records.get(id);
        if (old == null || old.version() != expected)
            throw new IllegalArgumentException("Skill changed; refresh before editing.");
        return old;
    }

    private InstructionSkill value(
            String id,
            SkillDraft d,
            InstructionSkill.Source source,
            UUID author,
            long created,
            long updated,
            int version,
            InstructionSkill.Activation activation) {
        return new InstructionSkill(
                id,
                d.name(),
                d.description(),
                d.body(),
                d.enabled(),
                scope,
                source,
                author,
                created,
                updated,
                version,
                activation,
                d.alwaysOn(),
                d.turnKinds(),
                d.triggers(),
                d.tags(),
                d.includeProfile());
    }

    private void putChecked(InstructionSkill skill) {
        long total = textSize(skill);
        for (InstructionSkill existing : records.values())
            if (!existing.id().equals(skill.id())) total += textSize(existing);
        if (total > limits.totalChars()) throw new IllegalArgumentException("Skill text budget reached.");
        records.put(skill.id(), skill);
        revision++;
    }

    static int textSize(InstructionSkill s) {
        return s.name().length()
                + s.description().length()
                + s.body().length()
                + s.triggers().stream().mapToInt(String::length).sum()
                + s.tags().stream().mapToInt(String::length).sum();
    }
}
