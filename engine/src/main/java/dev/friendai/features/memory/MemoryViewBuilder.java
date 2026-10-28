package dev.friendai.features.memory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Builds the memory tab's view from a book: groups first, then every fact when memory is enabled. */
public final class MemoryViewBuilder {
    private MemoryViewBuilder() {}

    public static MemoryView build(
            MemoryBook book,
            UUID owner,
            String ownerName,
            List<PlayerRef> online,
            boolean enabled,
            MemoryLimits limits,
            Optional<MenuStatus> status) {
        return build(book, owner, ownerName, online, enabled, limits, status, "");
    }

    /** As above, with the server's default personality for a book that has none of its own. */
    public static MemoryView build(
            MemoryBook book,
            UUID owner,
            String ownerName,
            List<PlayerRef> online,
            boolean enabled,
            MemoryLimits limits,
            Optional<MenuStatus> status,
            String serverPersona) {
        Map<UUID, String> onlineNames = new LinkedHashMap<>();
        for (PlayerRef ref : online) {
            onlineNames.put(ref.uuid(), ref.name());
        }

        List<MemoryView.Group> groups = new ArrayList<>();
        groups.add(new MemoryView.Group(
                owner.toString(),
                ownerName == null ? "" : ownerName,
                MemoryView.Role.OWNER,
                book.facts(Subject.player(owner)).size(),
                onlineNames.containsKey(owner)));
        groups.add(new MemoryView.Group(
                "world", "", MemoryView.Role.WORLD, book.facts(Subject.WORLD).size(), false));

        Map<UUID, String> others = new LinkedHashMap<>();
        for (PlayerRecord record : book.players()) {
            if (record.uuid().equals(owner)) {
                continue;
            }
            if (record.trusted() || !book.facts(Subject.player(record.uuid())).isEmpty()) {
                others.put(record.uuid(), record.lastName());
            }
        }
        for (PlayerRef ref : online) {
            if (!ref.uuid().equals(owner)) {
                others.putIfAbsent(ref.uuid(), ref.name());
            }
        }

        List<MemoryView.Group> rest = new ArrayList<>();
        for (Map.Entry<UUID, String> entry : others.entrySet()) {
            UUID uuid = entry.getKey();
            boolean isOnline = onlineNames.containsKey(uuid);
            String name = label(isOnline ? onlineNames.get(uuid) : entry.getValue());
            rest.add(new MemoryView.Group(
                    uuid.toString(),
                    name,
                    book.isTrusted(uuid) ? MemoryView.Role.TRUSTED : MemoryView.Role.GUEST,
                    book.facts(Subject.player(uuid)).size(),
                    isOnline));
        }
        rest.sort(Comparator.comparing(MemoryView.Group::name, String.CASE_INSENSITIVE_ORDER)
                .thenComparing(MemoryView.Group::key));
        groups.addAll(rest);
        if (groups.size() > MemoryViewCodec.MAX_GROUPS) {
            groups = new ArrayList<>(groups.subList(0, MemoryViewCodec.MAX_GROUPS));
        }

        List<MemoryView.FactRow> facts = new ArrayList<>();
        if (enabled) {
            for (Fact fact : book.allFacts()) {
                String author = Objects.equals(fact.authorUuid(), owner) ? "" : label(fact.authorName());
                facts.add(
                        new MemoryView.FactRow(fact.id(), fact.subject().key(), fact.category(), fact.text(), author));
            }
            if (facts.size() > MemoryViewCodec.MAX_FACTS) {
                facts = new ArrayList<>(facts.subList(0, MemoryViewCodec.MAX_FACTS));
            }
        }
        MemoryView.PersonaView persona = new MemoryView.PersonaView(
                book.persona().orElse(serverPersona == null ? "" : serverPersona),
                book.persona().isPresent());
        return new MemoryView(enabled, limits, List.copyOf(groups), List.copyOf(facts), status, persona);
    }

    private static String label(String name) {
        return name == null || name.isBlank() ? "?" : name;
    }
}
