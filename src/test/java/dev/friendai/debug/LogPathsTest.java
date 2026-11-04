package dev.friendai.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LogPathsTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final Path GAME = Path.of("C:/game");
    private static final Path WORLD = Path.of("C:/game/saves/New World");

    @Test
    void derivesTheGameRoot() {
        assertEquals(Path.of("C:/game/logs/friendai"), LogPaths.gameRoot(GAME));
    }

    @Test
    void derivesTheWorldRoot() {
        assertEquals(Path.of("C:/game/saves/New World/friendai/logs"), LogPaths.worldRoot(WORLD));
    }

    @Test
    void derivesTheOwnerRoot() {
        assertEquals(
                Path.of("C:/game/saves/New World/friendai/logs/owners/" + OWNER), LogPaths.ownerRoot(WORLD, OWNER));
    }

    @Test
    void keepsAnUnresolvedWorldApart() {
        assertEquals(Path.of("C:/game/logs/friendai/unavailable"), LogPaths.unavailableRoot(GAME));
    }

    @Test
    void namesTheWorld() {
        assertEquals("New World", LogPaths.worldName(WORLD));
    }

    @Test
    void namesNothingWhenTheWorldIsUnknown() {
        assertEquals("", LogPaths.worldName(null));
    }
}
