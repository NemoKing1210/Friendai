package dev.friendai.core;

import dev.friendai.agent.turn.*;
import dev.friendai.config.*;
import dev.friendai.execution.tool.*;
import dev.friendai.foundation.diagnostics.SecretRedactor;
import dev.friendai.foundation.teaching.TeachingContext;
import dev.friendai.memory.*;
import dev.friendai.skill.*;
import java.nio.file.Path;
import java.time.Clock;
import java.util.*;
import java.util.function.*;

/** Server-owned bridge; all calls are serialized by the server thread. */
public final class SkillService implements AutoCloseable {
    private final Supplier<ServerConfig> config;
    private final MemoryStore memory;
    private final SkillStore store;
    private final UnaryOperator<String> redact;
    private final SkillActions actions;

    public SkillService(Path dir, Supplier<ServerConfig> config, MemoryStore memory) {
        this.config = config;
        this.memory = memory;
        redact = SecretRedactor.of(() -> config.get().profiles.values().stream()
                .map(p -> SecretResolver.resolve(p.apiKey, System::getenv))
                .toList());
        var builtins = BuiltinSkills.load(SkillService.class.getClassLoader());
        store = new SkillStore(dir, SkillLimits.defaults(), redact);
        actions = new SkillActions(store, builtins, Clock.systemUTC(), redact, this::limits);
    }

    private SkillLimits limits() {
        var c = config.get().skills;
        return new SkillLimits(c.ownerCount, c.serverCount, c.totalChars);
    }

    public List<InstructionSkill> globalListed() {
        return safe(store.global().snapshot());
    }

    public SkillStore store() {
        return store;
    }

    public SkillActions actions() {
        return actions;
    }

    public List<InstructionSkill> listed(UUID owner) {
        return safe(actions.listed(owner));
    }

    private List<InstructionSkill> safe(List<InstructionSkill> skills) {
        return skills.stream()
                .filter(s -> {
                    try {
                        SkillText.validate(s.draft(), redact);
                        return true;
                    } catch (RuntimeException e) {
                        return false;
                    }
                })
                .toList();
    }

    public SkillPromptSession begin(
            UUID owner,
            Caller caller,
            String message,
            TurnKind turn,
            String taskId,
            boolean freeTime,
            boolean planning) {
        return begin(owner, caller, message, turn, taskId, freeTime, planning, false);
    }

    public SkillPromptSession begin(
            UUID owner,
            Caller caller,
            String message,
            TurnKind turn,
            String taskId,
            boolean freeTime,
            boolean planning,
            boolean combat) {
        try {
            Set<InstructionSkill.Kind> kinds =
                    EnumSet.of(turn == TurnKind.PLAYER ? InstructionSkill.Kind.CHAT : InstructionSkill.Kind.EVENT);
            if (freeTime) kinds.add(InstructionSkill.Kind.FREE_TIME);
            if (planning) kinds.add(InstructionSkill.Kind.PLANNING);
            if (taskId.contains("build") || taskId.contains("place")) kinds.add(InstructionSkill.Kind.BUILDING);
            if (combat || taskId.contains("attack") || taskId.contains("combat"))
                kinds.add(InstructionSkill.Kind.COMBAT);
            MemoryBook book = memory.book(owner);
            boolean owns = caller != null && caller.access() == AccessLevel.OWNER && owner.equals(caller.uuid());
            OwnerProfile profile = owns ? book.ownerProfile() : OwnerProfile.EMPTY;
            CompanionDescription description = owns ? book.description() : CompanionDescription.EMPTY;
            SkillContext context = new SkillContext(
                    caller, message, taskId, kinds, owns && profile.language().equals("auto"), description, profile);
            return new SkillPromptSession(
                    config.get().skills.enabled ? safe(actions.effective(owner)) : List.of(),
                    context,
                    config.get().agent.promptBudget,
                    config.get().skills.enabled ? config.get().skills.promptBudget : 0);
        } catch (RuntimeException e) {
            return SkillPromptSession.NONE;
        }
    }

    public ToolResult teach(UUID owner, Caller caller, com.google.gson.JsonObject args) {
        try {
            if (!config.get().skills.enabled) return ToolResult.error("Instruction skills are disabled.");
            var serverContext = teachingContextSource.apply(owner);
            if (!TeachingPolicy.authorize(
                    serverContext, caller, serverContext == null ? -1 : serverContext.generation()))
                return ToolResult.error("Skills may only be learned after a completed goal or shift.");
            SkillBook book = store.book(owner);
            book.setLimits(limits());
            TeachingLedger ledger = new TeachingLedger(book);
            var cfg = config.get().skills;
            long now = System.currentTimeMillis();
            if (!ledger.available(serverContext.outcomeId(), now, cfg.modelWritesPerHour, cfg.writesPerOutcome))
                return ToolResult.error("Instruction write allowance exhausted.");
            String action = args.get("action").getAsString();
            InstructionSkill changed = null;
            if (action.equals("create")) {
                changed = book.create(
                        SkillTransfer.read(args.toString()),
                        InstructionSkill.Source.MODEL,
                        owner,
                        now,
                        cfg.requireApproval);
            } else {
                String id = args.get("id").getAsString();
                InstructionSkill old = book.find(id).orElseThrow();
                if (old.source() != InstructionSkill.Source.MODEL || !owner.equals(old.author()))
                    return ToolResult.error("Only your own learned procedures can be changed.");
                int version = args.get("expectedVersion").getAsInt();
                switch (action) {
                    case "update" -> changed = book.edit(id, version, SkillTransfer.read(args.toString()), now);
                    case "disable" -> {
                        var d = old.draft();
                        changed = book.edit(
                                id,
                                version,
                                new SkillDraft(
                                        d.name(),
                                        d.description(),
                                        d.body(),
                                        false,
                                        d.alwaysOn(),
                                        d.turnKinds(),
                                        d.triggers(),
                                        d.tags(),
                                        d.includeProfile()),
                                now);
                    }
                    case "delete" -> book.delete(id, version);
                    default -> {
                        return ToolResult.error("Unknown instruction action.");
                    }
                }
            }
            ledger.recordSuccessfulWrite(serverContext.outcomeId(), now);
            return ToolResult.ok(
                    changed == null
                            ? "Instruction skill deleted."
                            : "Instruction skill " + changed.id() + " v" + changed.version() + " "
                                    + changed.activation());
        } catch (RuntimeException | StackOverflowError e) {
            return ToolResult.error("Invalid instruction change; check fields, limits and version.");
        }
    }

    private java.util.function.Function<UUID, TeachingContext> teachingContextSource = owner -> null;

    public void setTeachingContextSource(java.util.function.Function<UUID, TeachingContext> source) {
        teachingContextSource = source;
    }

    public void tick() {
        store.flushIfDue();
    }

    @Override
    public void close() {
        store.close();
    }
}
