package dev.friendai.features.memory.minecraft.tool;

import com.google.gson.JsonObject;
import dev.friendai.agent.tools.Cost;
import dev.friendai.agent.tools.ToolGroup;
import dev.friendai.agent.tools.ToolMeta;
import dev.friendai.agent.turn.LogActions;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import dev.friendai.execution.tool.SchemaBuilder;
import dev.friendai.execution.tool.Tool;
import dev.friendai.execution.tool.ToolResult;
import dev.friendai.features.memory.MemoryAudit;
import dev.friendai.features.memory.MemoryBook;
import dev.friendai.features.memory.MemoryCompactor;
import dev.friendai.features.memory.MemoryLimits;
import dev.friendai.features.memory.PlayerRecord;
import dev.friendai.features.memory.Subject;
import dev.friendai.features.memory.transcript.TranscriptActions;
import dev.friendai.foundation.diagnostics.LogKind;
import dev.friendai.foundation.text.TextBudget;
import dev.friendai.server.execution.tools.FeatureToolContributor;
import dev.friendai.server.execution.tools.FeatureToolRegistry;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** Tools that let the model read its own records: activity, conversations, memory, and compact its memory. */
public final class SelfTools implements FeatureToolContributor {
    private final SelfAccess self;

    public SelfTools(SelfAccess self) {
        this.self = Objects.requireNonNull(self);
    }

    @Override
    public void register(FeatureToolRegistry registry) {
        registry.registerTool(
                callerTool(
                        "read_log",
                        "Read your own recent activity: what you were told, what you answered, which tools you called and "
                                + "what they returned, errors and task events. Ids let you continue from the last line. "
                                + "Use it after a failure or when the owner asks what happened.",
                        SchemaBuilder.object()
                                .enumeration(
                                        "kind",
                                        "Only entries of this kind",
                                        "ALL",
                                        "USER",
                                        "ASSISTANT",
                                        "TOOL_CALL",
                                        "TOOL_RESULT",
                                        "ERROR",
                                        "TASK",
                                        "MANUAL")
                                .integer("since", "Only entries with an id greater than this", 0, Integer.MAX_VALUE)
                                .integer("before", "Only entries with an id smaller than this", 0, Integer.MAX_VALUE)
                                .integer("limit", "How many entries to return", 1, 200)
                                .enumeration("order", "Newest or oldest first", "newest", "oldest")
                                .build(),
                        AccessLevel.OWNER,
                        this::readLog),
                meta("read_log"));
        registry.registerTool(
                callerTool(
                        "recent_chat",
                        "Read the last messages you exchanged with a player, your own replies included, with ids and "
                                + "relative times. Use it when the owner asks what you talked about or what was agreed.",
                        SchemaBuilder.object()
                                .string("player", "Player name, 'me' (the current speaker) or 'owner'")
                                .integer("limit", "How many lines to return", 1, 50)
                                .integer("minutes", "Only lines from the last N minutes", 1, 10_080)
                                .integer("before", "Only lines with an id smaller than this", 0, Integer.MAX_VALUE)
                                .build(),
                        AccessLevel.TRUSTED,
                        this::recentChat),
                meta("recent_chat"));
        registry.registerTool(
                callerTool(
                        "memory_overview",
                        "See every fact with its id, category, subject, author and age, and how full each facts book is. "
                                + "Use it before forgetting or compressing, instead of discovering a full book from an error.",
                        SchemaBuilder.object()
                                .string(
                                        "subject",
                                        "'me', 'owner', 'world' or a player name; blank lists what you may see")
                                .integer("limit", "How many facts to list", 1, 100)
                                .bool("detail", "List the facts; a named subject implies true")
                                .build(),
                        AccessLevel.GUEST,
                        this::memoryOverview),
                meta("memory_overview"));
        registry.registerTool(
                callerTool(
                        "compress_memory",
                        "Propose safe merges of duplicate or superseded facts, or apply them when the owner agrees. A dry run "
                                + "is the default and lists every merge and its replacement text; promises are never merged "
                                + "and no fact is lost without a replacement.",
                        SchemaBuilder.object()
                                .bool("apply", "False (default) shows the plan; true performs it")
                                .string(
                                        "subject",
                                        "'me', 'owner', 'world' or a player name; blank covers the whole book")
                                .build(),
                        AccessLevel.OWNER,
                        this::compressMemory),
                meta("compress_memory"));
    }

    private ToolResult memoryOverview(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        if (owner == null) return ToolResult.error("The companion has no owner to read memory for.");
        String subject = args.has("subject") && !args.get("subject").isJsonNull()
                ? args.get("subject").getAsString()
                : "";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 40;
        Boolean detail = args.has("detail") ? args.get("detail").getAsBoolean() : null;
        return ToolResult.ok(MemoryAudit.overview(
                self.book(owner),
                owner,
                caller,
                self.memoryLimits(),
                self.compactAtPercent(),
                self.now(),
                subject,
                limit,
                detail,
                self.resultBudget(),
                self.redactor()));
    }

    private ToolResult compressMemory(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        if (owner == null || caller == null || caller.access() != AccessLevel.OWNER || !owner.equals(caller.uuid()))
            return ToolResult.error("Only the owner may compress memory.");
        MemoryBook book = self.book(owner);
        Subject filter = MemoryAudit.resolveSubject(
                book, owner, caller, args.has("subject") ? args.get("subject").getAsString() : "");
        boolean apply = args.has("apply") && args.get("apply").getAsBoolean();
        if (apply && !self.compactionEnabled()) return ToolResult.error("Compaction is disabled in the server config.");
        MemoryLimits limits = self.memoryLimits();
        MemoryCompactor.Plan plan =
                MemoryCompactor.plan(book, owner, filter, limits, true, MemoryCompactor.DEFAULT_MAX_MERGES);
        if (plan.empty()) {
            return ToolResult.ok("No safe merges found; "
                    + MemoryAudit.counts(book, owner, caller, limits, self.compactAtPercent())
                    + " Forget a fact by id if space is needed.");
        }
        if (!apply) {
            return ToolResult.ok(renderPlan(plan, self.resultBudget())
                    + "\nNo fact has changed. Call compress_memory with apply:true when the owner agrees.");
        }
        String ownerName = book.player(owner).map(PlayerRecord::lastName).orElse("owner");
        // A completed compaction is the one place the manner may drift, one small step per talk.
        companion.driftTrait();
        return ToolResult.ok(renderApplied(
                MemoryCompactor.apply(book, plan, owner, ownerName, self.now(), limits), self.resultBudget()));
    }

    private String renderPlan(MemoryCompactor.Plan plan, int maxChars) {
        List<String> lines = new ArrayList<>();
        UnaryOperator<String> clean = self.redactor();
        for (MemoryCompactor.Merge merge : plan.merges()) {
            lines.add("[" + joinIds(merge.ids()) + "] " + merge.category().name() + ": \"" + clean.apply(merge.text())
                    + "\" (" + merge.reason() + ")");
        }
        TextBudget.Fit fit = TextBudget.fit(lines, maxChars);
        StringBuilder text = new StringBuilder("Dry run: ")
                .append(plan.merges().size())
                .append(" merge(s), facts ")
                .append(plan.before())
                .append(" -> ")
                .append(plan.after())
                .append(".\n")
                .append(TextBudget.join(fit));
        if (fit.truncated()) text.append("\ntruncated: true");
        return text.toString();
    }

    private String renderApplied(MemoryCompactor.Applied applied, int maxChars) {
        StringBuilder text = new StringBuilder("Compacted: ")
                .append(applied.added())
                .append(" merge(s), facts ")
                .append(applied.before())
                .append(" -> ")
                .append(applied.after())
                .append(", ")
                .append(applied.removed())
                .append(" removed.");
        if (!applied.skipped().isEmpty())
            text.append(" Skipped ").append(applied.skipped().size()).append(" merge(s) that could not be applied.");
        List<String> lines = new ArrayList<>();
        UnaryOperator<String> clean = self.redactor();
        for (int i = 0; i < applied.applied().size(); i++) {
            MemoryCompactor.Merge merge = applied.applied().get(i);
            int newId = i < applied.newIds().size() ? applied.newIds().get(i) : 0;
            lines.add("[" + joinIds(merge.ids()) + "] -> [" + newId + "] "
                    + merge.category().name() + ": \"" + clean.apply(merge.text()) + "\"");
        }
        if (!lines.isEmpty()) text.append('\n').append(TextBudget.join(TextBudget.fit(lines, maxChars)));
        return text.toString();
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder text = new StringBuilder();
        for (int id : ids) {
            if (text.length() > 0) text.append('+');
            text.append(id);
        }
        return text.toString();
    }

    /** Guidance on when to look lives in the role-aware prompt section, so a guest never sees an owner-only tool. */
    private static ToolMeta meta(String name) {
        return new ToolMeta(name, ToolGroup.MEMORY, true, "", List.of(), Cost.LOW);
    }

    private ToolResult readLog(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        Set<LogKind> kinds = kinds(args);
        long since = args.has("since") ? args.get("since").getAsLong() : 0;
        long before = args.has("before") ? args.get("before").getAsLong() : 0;
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 40;
        boolean newest = !args.has("order") || args.get("order").getAsString().equalsIgnoreCase("newest");
        return ToolResult.ok(LogActions.read(
                caller,
                owner,
                self.log(owner),
                kinds,
                since,
                before,
                limit,
                newest,
                self.resultBudget(),
                self.redactor()));
    }

    private ToolResult recentChat(CompanionEntity companion, Caller caller, JsonObject args) {
        UUID owner = self.owner(companion);
        if (owner == null) return ToolResult.error("The companion has no owner to read a conversation for.");
        String player = args.has("player") && !args.get("player").isJsonNull()
                ? args.get("player").getAsString()
                : "me";
        int limit = args.has("limit") ? args.get("limit").getAsInt() : 20;
        int minutes = args.has("minutes") ? args.get("minutes").getAsInt() : 0;
        long before = args.has("before") ? args.get("before").getAsLong() : 0;
        return ToolResult.ok(TranscriptActions.recent(
                self.transcript(owner),
                owner,
                caller,
                player,
                limit,
                minutes,
                before,
                self.now(),
                self.resultBudget(),
                self.transcriptLimits(),
                self.redactor()));
    }

    private static Set<LogKind> kinds(JsonObject args) {
        if (!args.has("kind") || args.get("kind").isJsonNull()) return Set.of();
        String value = args.get("kind").getAsString();
        if (value.equalsIgnoreCase("ALL")) return Set.of();
        try {
            return EnumSet.of(LogKind.valueOf(value.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown log kind " + value + ".");
        }
    }

    private Tool<CompanionEntity> callerTool(
            String name, String description, JsonObject schema, AccessLevel min, Action action) {
        return new Tool<>() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public JsonObject schema() {
                return schema;
            }

            @Override
            public AccessLevel minAccess() {
                return min;
            }

            @Override
            public boolean enabled() {
                return self.enabled();
            }

            @Override
            public ToolResult execute(CompanionEntity companion, JsonObject args) {
                return run(companion, new Caller(null, "", AccessLevel.GUEST), args);
            }

            @Override
            public ToolResult execute(CompanionEntity companion, Caller caller, JsonObject args) {
                return run(companion, caller, args);
            }

            private ToolResult run(CompanionEntity companion, Caller caller, JsonObject args) {
                try {
                    return action.run(companion, caller, args);
                } catch (IllegalArgumentException e) {
                    return ToolResult.error(e.getMessage());
                }
            }
        };
    }

    @FunctionalInterface
    private interface Action {
        ToolResult run(CompanionEntity companion, Caller caller, JsonObject args);
    }
}
