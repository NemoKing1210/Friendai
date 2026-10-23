package dev.friendai.memory;

import java.util.ArrayList;
import java.util.List;

/** The on-disk shape of a memory file. Gson POJO; keep field names stable for old saves. */
public final class MemoryFile {
    public int version = 1;
    public int nextId = 1;
    public List<PlayerEntry> players = new ArrayList<>();
    public List<FactEntry> facts = new ArrayList<>();

    public static final class PlayerEntry {
        public String uuid;
        public String lastName;
        public boolean trusted;
        public long lastSeen;
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
