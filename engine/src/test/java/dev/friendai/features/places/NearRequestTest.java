package dev.friendai.features.places;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class NearRequestTest {
    private static NearRequest parse(String json) {
        return NearRequest.parse(JsonParser.parseString(json).getAsJsonObject());
    }

    @Test
    void noNearMeansTheCompanion() {
        assertEquals(new NearRequest.Companion(), parse("{}"));
        assertEquals(new NearRequest.Companion(), parse("{\"near\":\"\"}"));
        assertEquals(new NearRequest.Companion(), parse("{\"near\":\"  \"}"));
    }

    @Test
    void selfAndCompanionNameTheCompanionToo() {
        assertEquals(new NearRequest.Companion(), parse("{\"near\":\"self\"}"));
        assertEquals(new NearRequest.Companion(), parse("{\"near\":\" Companion \"}"));
    }

    @Test
    void theSpeakerIsOnlyTheNamedException() {
        assertEquals(new NearRequest.Speaker(), parse("{\"near\":\"me\"}"));
        assertEquals(new NearRequest.Speaker(), parse("{\"near\":\"You\"}"));
        assertEquals(new NearRequest.Speaker(), parse("{\"near\":\"speaker\"}"));
    }

    @Test
    void aPlacePrefixNamesAPlace() {
        assertEquals(new NearRequest.Place("home"), parse("{\"near\":\"place:home\"}"));
        assertEquals(new NearRequest.Place("The Farm"), parse("{\"near\":\"Place: The Farm\"}"));
        assertEquals(new NearRequest.Place(""), parse("{\"near\":\"place:\"}"));
    }

    @Test
    void coordinatesWinAndMustBeComplete() {
        assertEquals(new NearRequest.Coordinates(1, 2, 3), parse("{\"x\":1,\"y\":2,\"z\":3}"));
        assertEquals(new NearRequest.Coordinates(1, 2, 3), parse("{\"x\":1,\"y\":2,\"z\":3,\"near\":\"me\"}"));
        assertEquals(new NearRequest.Coordinates(-5, 70, 900), parse("{\"x\":-5,\"y\":70,\"z\":900}"));
        assertThrows(IllegalArgumentException.class, () -> parse("{\"x\":1,\"y\":2}"));
    }

    @Test
    void unknownWordsAreRefused() {
        IllegalArgumentException error =
                assertThrows(IllegalArgumentException.class, () -> parse("{\"near\":\"there\"}"));
        assertEquals("near must be \"self\", \"me\" or \"place:<name>\", or give x, y and z", error.getMessage());
    }

    @Test
    void aJsonNullNearBehavesAsAbsent() {
        JsonObject args = JsonParser.parseString("{\"near\":null}").getAsJsonObject();
        assertEquals(new NearRequest.Companion(), NearRequest.parse(args));
    }
}
