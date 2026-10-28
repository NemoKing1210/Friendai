package dev.friendai.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Applies one request from the memory tab with the owner's rights. The book is the sender's, chosen by the
 * caller; no request argument selects an owner. Methods never throw: every failure becomes a {@link MenuStatus}
 * with a translation key.
 */
public final class MemoryMenuActions {
    private final MemoryBook book;
    private final UUID owner;
    private final String ownerName;
    private final boolean enabled;
    private final MemoryLimits limits;
    private final long now;
    private final Function<UUID, Optional<PlayerRef>> onlineById;

    public MemoryMenuActions(
            MemoryBook book,
            UUID owner,
            String ownerName,
            boolean enabled,
            MemoryLimits limits,
            long now,
            Function<UUID, Optional<PlayerRef>> onlineById) {
        this.book = book;
        this.owner = owner;
        this.ownerName = ownerName;
        this.enabled = enabled;
        this.limits = limits;
        this.now = now;
        this.onlineById = onlineById;
    }

    public MenuStatus add(String groupKey, String json) {
        if (!enabled) {
            return error("friendai.menu.memory.error.disabled");
        }
        Optional<Subject> group = resolveGroup(groupKey);
        if (group.isEmpty()) {
            return error("friendai.menu.memory.error.unknown_player");
        }
        try {
            Draft draft = draft(json);
            Fact fact = book.addFact(group.get(), draft.category(), draft.text(), owner, ownerName, now, limits);
            return ok("friendai.menu.memory.status.added", fact.id());
        } catch (MemoryLimitException e) {
            return limitError(e);
        } catch (RuntimeException e) {
            return error("friendai.menu.memory.error.bad_request");
        }
    }

    public MenuStatus edit(String id, String json) {
        if (!enabled) {
            return error("friendai.menu.memory.error.disabled");
        }
        Integer factId = factId(id);
        if (factId == null) {
            return error("friendai.menu.memory.error.bad_request");
        }
        try {
            Draft draft = draft(json);
            book.editFact(factId, draft.category(), draft.text(), owner, ownerName, now, limits);
            return ok("friendai.menu.memory.status.saved", factId);
        } catch (MemoryLimitException e) {
            return limitError(e);
        } catch (RuntimeException e) {
            return error("friendai.menu.memory.error.bad_request");
        }
    }

    public MenuStatus forget(String id) {
        if (!enabled) {
            return error("friendai.menu.memory.error.disabled");
        }
        Integer factId = factId(id);
        if (factId == null) {
            return error("friendai.menu.memory.error.bad_request");
        }
        if (!book.removeFact(factId)) {
            return error("friendai.menu.memory.error.no_fact", factId);
        }
        return ok("friendai.menu.memory.status.forgotten", factId);
    }

    public MenuStatus clear(String groupKey) {
        if (!enabled) {
            return error("friendai.menu.memory.error.disabled");
        }
        Optional<Subject> group = resolveGroup(groupKey);
        if (group.isEmpty()) {
            return error("friendai.menu.memory.error.unknown_player");
        }
        return ok("friendai.menu.memory.status.cleared", book.clearFacts(group.get()));
    }

    public MenuStatus trust(String uuid, String flag) {
        if (!"true".equals(flag) && !"false".equals(flag)) {
            return error("friendai.menu.memory.error.bad_request");
        }
        boolean trust = "true".equals(flag);
        UUID target;
        try {
            target = UUID.fromString(uuid == null ? "" : uuid.strip());
        } catch (IllegalArgumentException e) {
            return error("friendai.menu.memory.error.unknown_player");
        }
        if (target.equals(owner)) {
            return error("friendai.menu.memory.error.self_trust");
        }
        Optional<PlayerRef> online = onlineById.apply(target);
        Optional<PlayerRecord> record = book.player(target);
        if (record.isEmpty() && online.isEmpty()) {
            return error("friendai.menu.memory.error.unknown_player");
        }
        String name = online.map(PlayerRef::name)
                .filter(n -> n != null && !n.isBlank())
                .or(() -> record.map(PlayerRecord::lastName).filter(n -> n != null && !n.isBlank()))
                .orElse("?");
        if (record.isEmpty()) {
            book.ensurePlayer(target, name);
        }
        book.setTrusted(target, name, trust, now);
        return ok(trust ? "friendai.menu.memory.status.trusted" : "friendai.menu.memory.status.untrusted", name);
    }

    /**
     * Sets the owner's companion personality, or returns to the server default when {@code text} is blank. It does
     * not depend on {@code memory.enabled}: the personality is not a stored fact.
     */
    public MenuStatus persona(String text) {
        if (text == null || text.isBlank()) {
            book.clearPersona();
            return ok("friendai.menu.description.cleared");
        }
        try {
            book.setPersona(text);
            return ok("friendai.menu.description.saved");
        } catch (IllegalArgumentException e) {
            return error("friendai.menu.description.invalid");
        }
    }

    /** {@code world}, the owner's UUID or another player the book knows or who is online. */
    private Optional<Subject> resolveGroup(String key) {
        Subject subject = key == null ? null : Subject.parse(key.strip()).orElse(null);
        if (subject == null) {
            return Optional.empty();
        }
        if (subject instanceof Subject.World) {
            return Optional.of(subject);
        }
        UUID uuid = ((Subject.Player) subject).uuid();
        if (uuid.equals(owner) || book.player(uuid).isPresent()) {
            return Optional.of(Subject.player(uuid));
        }
        Optional<PlayerRef> online = onlineById.apply(uuid);
        if (online.isEmpty()) {
            return Optional.empty();
        }
        book.ensurePlayer(uuid, online.get().name());
        return Optional.of(Subject.player(uuid));
    }

    private record Draft(Category category, String text) {}

    private static Draft draft(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        Category category = Category.parse(stringField(object, "category"))
                .orElseThrow(() -> new IllegalArgumentException("unknown category"));
        return new Draft(category, stringField(object, "text"));
    }

    private static String stringField(JsonObject object, String name) {
        JsonElement element = object.get(name);
        if (element == null
                || !element.isJsonPrimitive()
                || !element.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("missing string field " + name);
        }
        return element.getAsString();
    }

    /** A positive decimal int, or null when the argument is anything else. */
    private static Integer factId(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int id = Integer.parseInt(raw.strip());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private MenuStatus limitError(MemoryLimitException e) {
        List<Object> values = e.values();
        return switch (e.kind()) {
            case EMPTY -> error("friendai.menu.memory.error.empty");
            case TOO_LONG -> error("friendai.menu.memory.error.too_long", values.get(0), values.get(1));
            case FULL -> {
                Subject subject = (Subject) values.get(0);
                int max = (Integer) values.get(1);
                if (subject instanceof Subject.World) {
                    yield error("friendai.menu.memory.error.full_world", max);
                }
                String name = book.player(((Subject.Player) subject).uuid())
                        .map(PlayerRecord::lastName)
                        .filter(n -> n != null && !n.isBlank())
                        .orElse("?");
                yield error("friendai.menu.memory.error.full", name, max);
            }
            case DUPLICATE -> error("friendai.menu.memory.error.duplicate", values.get(0));
            case NO_FACT -> error("friendai.menu.memory.error.no_fact", values.get(0));
        };
    }

    private static MenuStatus ok(String key, Object... args) {
        return MenuStatus.ok(key, args);
    }

    private static MenuStatus error(String key, Object... args) {
        return MenuStatus.error(key, args);
    }
}
