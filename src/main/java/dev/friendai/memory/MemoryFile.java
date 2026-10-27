package dev.friendai.memory;

import java.util.ArrayList;
import java.util.List;

/** The on-disk shape of a memory file. Gson POJO; keep field names stable for old saves. */
public final class MemoryFile {
    public int version = 4;
    public int nextId = 1;
    /** The name the owner gave their companion; null means the configured default. */
    public String companionName;
    /** The personality the owner gave their companion; null means the configured default. */
    public String persona;
    /** The encoded goal board ({@code goals:v1:...}); null means no goals. */
    public String goals;
    /** The encoded free-time state ({@code freetime:v1:...}); null means free time was never used. */
    public String freeTime;
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
