package dev.friedai.memory;

import java.util.Optional;
import java.util.UUID;

/** What a fact is about: one player (by UUID) or the shared world. */
public sealed interface Subject permits Subject.Player, Subject.World {
    Subject WORLD = new World();

    static Subject player(UUID uuid) {
        return new Player(uuid);
    }

    /** Parses the file representation: {@code "world"} or a UUID. */
    static Optional<Subject> parse(String key) {
        if (key == null || key.isBlank()) {
            return Optional.empty();
        }
        if (key.equals("world")) {
            return Optional.of(WORLD);
        }
        try {
            return Optional.of(player(UUID.fromString(key)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The file representation. */
    String key();

    record Player(UUID uuid) implements Subject {
        @Override
        public String key() {
            return uuid.toString();
        }
    }

    record World() implements Subject {
        @Override
        public String key() {
            return "world";
        }
    }
}
