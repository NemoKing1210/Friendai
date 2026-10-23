package dev.friedai.memory;

import java.util.ArrayList;
import java.util.List;

/** The on-disk shape of a memory file. Gson POJO; keep field names stable for old saves. */
public final class MemoryFile {
    public int version = 1;
    public List<PlayerEntry> players = new ArrayList<>();

    public static final class PlayerEntry {
        public String uuid;
        public String lastName;
        public boolean trusted;
        public long lastSeen;
    }
}
