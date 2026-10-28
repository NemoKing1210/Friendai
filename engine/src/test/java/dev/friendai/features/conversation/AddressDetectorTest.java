package dev.friendai.features.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddressDetectorTest {
    private final AddressDetector detector = new AddressDetector("Friendai", "@", true, false);

    @Test
    void prefixStripped() {
        assertEquals(Optional.of("come here"), detector.match("@ come here"));
        assertEquals(Optional.of("stay"), detector.match("@Friendai, stay"));
    }

    @Test
    void nameAtStartStripped() {
        assertEquals(Optional.of("mine 10 logs"), detector.match("Friendai, mine 10 logs"));
    }

    @Test
    void nameInMiddleKeepsWholeText() {
        assertEquals(Optional.of("so, Friendai, shall we go?"), detector.match("so, Friendai, shall we go?"));
    }

    @Test
    void ignoresNameAsSubstring() {
        assertEquals(Optional.empty(), detector.match("Friendaiplane is flying"));
        assertEquals(Optional.empty(), detector.match("superFriendai"));
    }

    @Test
    void cyrillicNameCaseInsensitive() {
        AddressDetector ru = new AddressDetector("Фрида", "@", true, false);
        assertEquals(Optional.of("иди"), ru.match("ФРИДА, иди"));
    }

    @Test
    void respondToNameOff() {
        AddressDetector off = new AddressDetector("Friendai", "@", false, false);
        assertEquals(Optional.empty(), off.match("Friendai, hi"));
        assertEquals(Optional.of("hi"), off.match("@hi"));
    }

    @Test
    void listenAll() {
        AddressDetector all = new AddressDetector("Friendai", "@", false, true);
        assertEquals(Optional.of("hello"), all.match("hello"));
    }

    @Test
    void notAddressedIsEmpty() {
        assertEquals(Optional.empty(), detector.match("hello there"));
    }

    @Test
    void nothingLeftReturnsOriginal() {
        assertEquals(Optional.of("Friendai"), detector.match("Friendai"));
        assertEquals(Optional.of("@"), detector.match("@"));
    }
}
