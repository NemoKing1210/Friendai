package dev.friendai.features.memory;

import java.util.Optional;

/** The online-player lookup the memory tools use. The Minecraft layer implements it. */
@FunctionalInterface
public interface PlayerDirectory {
    /** Case-insensitive; empty when nobody online has that name. */
    Optional<PlayerRef> findOnline(String name);
}
