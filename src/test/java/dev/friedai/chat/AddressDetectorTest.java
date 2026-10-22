package dev.friedai.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class AddressDetectorTest {
    private final AddressDetector detector = new AddressDetector("Friedai", "@", true, false);

    @Test
    void prefixStripped() {
        assertEquals(Optional.of("come here"), detector.match("@ come here"));
        assertEquals(Optional.of("stay"), detector.match("@Friedai, stay"));
    }

    @Test
    void nameAtStartStripped() {
        assertEquals(Optional.of("mine 10 logs"), detector.match("Friedai, mine 10 logs"));
    }

    @Test
    void nameInMiddleKeepsWholeText() {
        assertEquals(Optional.of("so, Friedai, shall we go?"), detector.match("so, Friedai, shall we go?"));
    }

    @Test
    void ignoresNameAsSubstring() {
        assertEquals(Optional.empty(), detector.match("Friedaiplane is flying"));
        assertEquals(Optional.empty(), detector.match("superFriedai"));
    }

    @Test
    void cyrillicNameCaseInsensitive() {
        AddressDetector ru = new AddressDetector("Фрида", "@", true, false);
        assertEquals(Optional.of("иди"), ru.match("ФРИДА, иди"));
    }

    @Test
    void respondToNameOff() {
        AddressDetector off = new AddressDetector("Friedai", "@", false, false);
        assertEquals(Optional.empty(), off.match("Friedai, hi"));
        assertEquals(Optional.of("hi"), off.match("@hi"));
    }

    @Test
    void listenAll() {
        AddressDetector all = new AddressDetector("Friedai", "@", false, true);
        assertEquals(Optional.of("hello"), all.match("hello"));
    }

    @Test
    void notAddressedIsEmpty() {
        assertEquals(Optional.empty(), detector.match("hello there"));
    }

    @Test
    void nothingLeftReturnsOriginal() {
        assertEquals(Optional.of("Friedai"), detector.match("Friedai"));
        assertEquals(Optional.of("@"), detector.match("@"));
    }
}
