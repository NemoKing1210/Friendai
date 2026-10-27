package dev.friendai.place;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PlaceKindTest {

    @Test
    void parseAcceptsEnumNamesAndSynonyms() {
        assertEquals(PlaceKind.HOUSE, PlaceKind.parse("Home"));
        assertEquals(PlaceKind.HOUSE, PlaceKind.parse("base"));
        assertEquals(PlaceKind.HOUSE, PlaceKind.parse("HOUSE"));
        assertEquals(PlaceKind.FARM, PlaceKind.parse("farm"));
        assertEquals(PlaceKind.MINE, PlaceKind.parse("Mine"));
        assertEquals(PlaceKind.STORAGE, PlaceKind.parse("chest"));
        assertEquals(PlaceKind.STORAGE, PlaceKind.parse("storage"));
        assertEquals(PlaceKind.PORTAL, PlaceKind.parse("portal"));
        assertEquals(PlaceKind.TRADE, PlaceKind.parse("village"));
        assertEquals(PlaceKind.TRADE, PlaceKind.parse("trade"));
        assertEquals(PlaceKind.LANDMARK, PlaceKind.parse("landmark"));
    }

    @Test
    void parseRejectsUnknownKindsAndListsTheChoices() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> PlaceKind.parse("castle"));
        assertTrue(error.getMessage().startsWith("unknown place kind; use "));
        assertTrue(error.getMessage().contains("house"));
        assertThrows(IllegalArgumentException.class, () -> PlaceKind.parse(" "));
        assertThrows(IllegalArgumentException.class, () -> PlaceKind.parse(null));
    }

    @Test
    void inferFromNameReadsTheWords() {
        assertEquals(PlaceKind.FARM, PlaceKind.inferFromName("wheat_farm"));
        assertEquals(PlaceKind.FARM, PlaceKind.inferFromName("iron farm"));
        assertEquals(PlaceKind.MINE, PlaceKind.inferFromName("shaft 2"));
        assertEquals(PlaceKind.MINE, PlaceKind.inferFromName("quarry"));
        assertEquals(PlaceKind.PORTAL, PlaceKind.inferFromName("nether portal"));
        assertEquals(PlaceKind.STORAGE, PlaceKind.inferFromName("chest shed"));
        assertEquals(PlaceKind.TRADE, PlaceKind.inferFromName("village trading"));
        assertEquals(PlaceKind.HOUSE, PlaceKind.inferFromName("cottage"));
        assertEquals(PlaceKind.HOUSE, PlaceKind.inferFromName(null));
    }
}
