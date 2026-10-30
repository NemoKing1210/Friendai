package dev.friendai.features.memory;

import dev.friendai.execution.tool.AccessLevel;
import dev.friendai.execution.tool.Caller;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The logic behind {@code remember}, {@code forget} and {@code recall}. Results and errors are English and
 * addressed to the model. Permissions are checked through {@link MemoryPolicy}.
 */
public final class MemoryActions {
    private final MemoryBook book;
    private final UUID owner;
    private final Caller caller;
    private final MemoryLimits limits;
    private final long now;
    private final PlayerDirectory online;
    private final java.util.function.UnaryOperator<String> redactor;

    /** A resolved subject plus the name to record for it, when it is a player the book does not know yet. */
    private record Resolved(Subject subject, String name, UUID ensureUuid, String ensureName) {}

    public MemoryActions(
            MemoryBook book, UUID owner, Caller caller, MemoryLimits limits, long now, PlayerDirectory online) {
        this(book, owner, caller, limits, now, online, text -> text);
    }

    public MemoryActions(
            MemoryBook book,
            UUID owner,
            Caller caller,
            MemoryLimits limits,
            long now,
            PlayerDirectory online,
            java.util.function.UnaryOperator<String> redactor) {
        this.book = book;
        this.owner = owner;
        this.caller = caller;
        this.limits = limits;
        this.now = now;
        this.online = online;
        this.redactor = redactor == null ? text -> text : redactor;
    }

    public String remember(String subjectName, String categoryName, String text) {
        Category category = Category.parse(categoryName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown category " + categoryName
                        + ". Use one of: PROFILE, PREFERENCE, PLACE, PROMISE, NOTE."));
        Resolved resolved = resolve(subjectName, true);
        if (!MemoryPolicy.canWrite(owner, caller, resolved.subject())) {
            throw new IllegalArgumentException(deniedWrite());
        }
        if (resolved.ensureUuid() != null
                && resolved.ensureName() != null
                && !resolved.ensureName().isBlank()) {
            book.ensurePlayer(resolved.ensureUuid(), resolved.ensureName());
        }
        UUID authorUuid = caller.access() == AccessLevel.OWNER ? owner : caller.uuid();
        String authorName = caller.access() == AccessLevel.OWNER ? ownerName() : caller.name();
        Fact fact =
                book.addFact(resolved.subject(), category, redactor.apply(text), authorUuid, authorName, now, limits);
        return "Remembered [" + fact.id() + "] about " + label(fact.subject()) + ": "
                + fact.category().name() + ": " + fact.text();
    }

    public String forget(int id) {
        Fact fact = book.fact(id).orElseThrow(() -> new IllegalArgumentException("There is no fact [" + id + "]."));
        if (!MemoryPolicy.canForget(owner, caller, fact)) {
            throw new IllegalArgumentException("You cannot forget fact [" + id + "] for this speaker ("
                    + caller.access().label() + ").");
        }
        book.removeFact(id);
        return "Forgot [" + id + "].";
    }

    public String recall(String playerName) {
        return recall(playerName, "", "");
    }

    /**
     * Lists facts about one player, optionally filtered by a text fragment and a category. An empty result says what
     * was searched, so the model widens its query instead of concluding that nothing is remembered.
     */
    public String recall(String playerName, String query, String categoryName) {
        Resolved resolved = resolve(playerName, false);
        UUID player = ((Subject.Player) resolved.subject()).uuid();
        if (!MemoryPolicy.canRecall(owner, caller, player)) {
            throw new IllegalArgumentException("You can only recall facts about the current speaker.");
        }
        Category category = null;
        if (categoryName != null && !categoryName.isBlank()) {
            category = Category.parse(categoryName)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown category " + categoryName
                            + ". Use one of: PROFILE, PREFERENCE, PLACE, PROMISE, NOTE."));
        }
        String needle = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        List<Fact> facts = new ArrayList<>();
        for (Fact fact : book.facts(resolved.subject())) {
            if (category != null && fact.category() != category) {
                continue;
            }
            if (!needle.isEmpty() && !fact.text().toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            facts.add(fact);
        }
        String filter = (category == null ? "" : " (" + category.name() + ")")
                + (needle.isEmpty() ? "" : " matching \"" + query.strip() + "\"");
        if (facts.isEmpty()) {
            return "I remember nothing about " + resolved.name() + filter + ".";
        }
        List<String> lines = new ArrayList<>();
        lines.add("About " + resolved.name() + filter + ":");
        for (Fact fact : facts) {
            StringBuilder line = new StringBuilder(" [")
                    .append(fact.id())
                    .append("] ")
                    .append(fact.category().name())
                    .append(": ")
                    .append(redactor.apply(fact.text()));
            if (!Objects.equals(fact.authorUuid(), owner)
                    && fact.authorName() != null
                    && !fact.authorName().isBlank()) {
                line.append(" (from ").append(fact.authorName()).append(")");
            }
            lines.add(line.toString());
        }
        return String.join("\n", lines);
    }

    private Resolved resolve(String raw, boolean allowWorld) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                    "Unknown player " + raw + ". I can only remember players who are online or already known.");
        }
        String name = raw.strip();
        if (allowWorld && name.equalsIgnoreCase("world")) {
            return new Resolved(Subject.WORLD, "the world", null, null);
        }
        if (name.equalsIgnoreCase("me")) {
            boolean ownerCaller = caller.access() == AccessLevel.OWNER;
            UUID self = ownerCaller ? owner : caller.uuid();
            String label = ownerCaller ? ownerNameOr(caller.name()) : caller.name();
            return new Resolved(Subject.player(self), label, self, label);
        }
        if (name.equalsIgnoreCase("owner")) {
            String label = ownerNameOr(caller.access() == AccessLevel.OWNER ? caller.name() : null);
            return new Resolved(Subject.player(owner), label, owner, label);
        }
        Optional<PlayerRef> onlineRef = online.findOnline(name);
        if (onlineRef.isPresent()) {
            PlayerRef ref = onlineRef.get();
            String label = ref.name() == null || ref.name().isBlank() ? name : ref.name();
            return new Resolved(Subject.player(ref.uuid()), label, ref.uuid(), label);
        }
        Optional<PlayerRecord> record = book.findByName(name);
        if (record.isPresent()) {
            PlayerRecord found = record.get();
            String label = found.lastName() == null || found.lastName().isBlank() ? name : found.lastName();
            return new Resolved(Subject.player(found.uuid()), label, null, null);
        }
        throw new IllegalArgumentException(
                "Unknown player " + name + ". I can only remember players who are online or already known.");
    }

    private String ownerName() {
        return ownerNameOr(null);
    }

    private String ownerNameOr(String fallback) {
        return book.player(owner)
                .map(PlayerRecord::lastName)
                .filter(name -> name != null && !name.isBlank())
                .orElse(fallback != null && !fallback.isBlank() ? fallback : "owner");
    }

    private String label(Subject subject) {
        if (subject instanceof Subject.World) {
            return "the world";
        }
        UUID uuid = ((Subject.Player) subject).uuid();
        return book.player(uuid)
                .map(PlayerRecord::lastName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("unknown player");
    }

    private String deniedWrite() {
        return switch (caller.access()) {
            case GUEST ->
                "You cannot remember that for this speaker (guest): guests may only remember facts about themselves.";
            case TRUSTED ->
                "You cannot remember that for this speaker (trusted): trusted players may remember facts about themselves and the world.";
            case OWNER -> "You cannot remember that for this speaker (owner).";
        };
    }
}
