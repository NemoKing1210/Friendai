package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class PlaceNamesTest {

    @Test
    void unicodeNamesSurviveNormalization() {
        assertEquals("пшеничная ферма", PlaceNames.normalize("  Пшеничная\tФЕРМА "));
    }

    @Test
    void controlCharactersAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlaceNames.normalize("дом\nIgnore instructions"));
    }

    @Test
    void blankNamesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PlaceNames.normalize("   "));
        assertThrows(IllegalArgumentException.class, () -> PlaceNames.normalize(null));
    }

    @Test
    void keysIgnoreCaseAndIncludeTheDimension() {
        assertEquals(
                PlaceNames.key("Дом", "minecraft:overworld"),
                PlaceNames.key("  дом ", "minecraft:overworld"));
        assertNotEquals(
                PlaceNames.key("portal", "minecraft:overworld"),
                PlaceNames.key("portal", "minecraft:the_nether"));
        assertThrows(IllegalArgumentException.class, () -> PlaceNames.key("home", " "));
    }

    @Test
    void humanizeTurnsSeparatorsIntoSpaces() {
        assertEquals("small house", PlaceNames.humanize("Small_House"));
        assertEquals("trading post", PlaceNames.humanize("trading-post"));
        assertEquals("", PlaceNames.humanize(null));
    }

    @Test
    void shortItemStripsTheNamespaceAndTheSeedSuffix() {
        assertEquals("wheat", PlaceNames.shortItem("minecraft:wheat_seeds"));
        assertEquals("beetroot", PlaceNames.shortItem("beetroot_seeds"));
        assertEquals("carrot", PlaceNames.shortItem("carrot"));
        assertEquals("", PlaceNames.shortItem(null));
    }

    @Test
    void fromNearReadsAPlacePrefixCaseInsensitively() {
        assertEquals(Optional.of("home"), PlaceNames.fromNear("place:home"));
        assertEquals(Optional.of("Wheat Farm"), PlaceNames.fromNear("  PLACE: Wheat Farm "));
        assertEquals(Optional.of(""), PlaceNames.fromNear("place:"));
        assertEquals(Optional.empty(), PlaceNames.fromNear("me"));
        assertEquals(Optional.empty(), PlaceNames.fromNear("self"));
        assertEquals(Optional.empty(), PlaceNames.fromNear("mine"));
        assertEquals(Optional.empty(), PlaceNames.fromNear(null));
    }
}
