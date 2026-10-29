package dev.friendai.features.memory;

import java.util.ArrayList;
import java.util.List;

/** Version-1 on-disk shape of memory facts and owner player records. */
public final class MemoryFile {
    public int nextId = 1;

    public List<PlayerEntry> players = new ArrayList<>();
    public List<FactEntry> facts = new ArrayList<>();
    public List<FoodRuleEntry> foodRules = new ArrayList<>();

    public static final class PlayerEntry {
        public String uuid;
        public String lastName;
        public boolean trusted;
        public long lastSeen;
        /** The language the owner writes in, learned from their messages; null on an old file or before it is known. */
        public String language;
    }

    /** One food the owner allowed or forbade; {@code allowed} false means an explicit ban. */
    public static final class FoodRuleEntry {
        public String item;
        public boolean allowed;
    }

    public static final class FactEntry {
        public int id;
        public String subject;
        public String category;
        public String text;
        public String authorUuid;
        public String authorName;
        public long createdAt;
    }
}
