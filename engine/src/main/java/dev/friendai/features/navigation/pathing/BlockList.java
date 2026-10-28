package dev.friendai.features.navigation.pathing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** A list of block ids and block tags, as the config spells them: {@code namespace:path} or {@code #namespace:path}. */
public final class BlockList {

    private static final Pattern ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    private final Set<String> ids;
    private final Set<String> tags;

    private BlockList(Set<String> ids, Set<String> tags) {
        this.ids = ids;
        this.tags = tags;
    }

    /** Parses entries, reporting every malformed one to {@code unknownEntry} and skipping it. */
    public static BlockList parse(List<String> entries, Consumer<String> unknownEntry) {
        Set<String> ids = new HashSet<>();
        Set<String> tags = new HashSet<>();
        if (entries != null) {
            for (String entry : entries) {
                if (entry == null) {
                    continue;
                }
                String trimmed = entry.trim();
                if (trimmed.startsWith("#")) {
                    String id = trimmed.substring(1);
                    if (ID.matcher(id).matches()) {
                        tags.add(id);
                    } else {
                        unknownEntry.accept(entry);
                    }
                } else if (ID.matcher(trimmed).matches()) {
                    ids.add(trimmed);
                } else {
                    unknownEntry.accept(entry);
                }
            }
        }
        return new BlockList(Set.copyOf(ids), Set.copyOf(tags));
    }

    public boolean matches(String blockId, Set<String> blockTags) {
        if (ids.contains(blockId)) {
            return true;
        }
        for (String tag : blockTags) {
            if (tags.contains(tag)) {
                return true;
            }
        }
        return false;
    }

    public List<String> entries() {
        List<String> all = new ArrayList<>(ids);
        tags.forEach(tag -> all.add("#" + tag));
        return all;
    }
}
