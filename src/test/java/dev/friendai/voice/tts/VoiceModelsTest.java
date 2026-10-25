package dev.friendai.voice.tts;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class VoiceModelsTest {
    @Test
    void catalogIsConsistent() {
        List<VoiceModel> all = VoiceModels.all();
        assertFalse(all.isEmpty());
        Set<String> ids = new HashSet<>();
        for (VoiceModel model : all) {
            assertTrue(ids.add(model.id()), "duplicate id " + model.id());
            assertTrue(model.url().startsWith("https://"), model.id());
            assertTrue(model.size() > 0, model.id());
            assertTrue(model.sha256().matches("[0-9a-f]{64}"), model.id());
            assertFalse(model.license().isBlank(), model.id());
            assertEquals(model.id() + ".onnx", model.modelFile(), model.id());
            assertFalse(model.tokensFile().isBlank(), model.id());
            assertFalse(model.dataDir().isBlank(), model.id());
            assertTrue(VoiceModels.byId(model.id()).isPresent(), model.id());
        }
    }

    @Test
    void offersFiveVoicesIncludingLibriTts() {
        assertEquals(5, VoiceModels.all().size());
        VoiceModel libri = VoiceModels.byId("en_US-libritts_r-medium").orElseThrow();
        assertEquals(82_038_311L, libri.size());
        assertEquals("10dc268f3e371696d721486123e2705a9fc1faa113491979fde4d88dba1f1b1c", libri.sha256());
        assertEquals("en_US-libritts_r-medium.onnx", libri.modelFile());
        assertEquals("CC BY 4.0", libri.license());
        assertEquals("ru_RU-irina-medium", VoiceModels.defaultFor("ru_ru").id());
        assertEquals("en_US-lessac-medium", VoiceModels.defaultFor("en_us").id());
    }

    @Test
    void defaultVoiceFollowsTheGameLanguage() {
        assertEquals("ru_RU-irina-medium", VoiceModels.defaultFor("ru_ru").id());
        assertEquals("en_US-lessac-medium", VoiceModels.defaultFor("de_de").id());
        assertEquals("en_US-lessac-medium", VoiceModels.defaultFor(null).id());
    }

    @Test
    void unknownIdIsEmpty() {
        assertTrue(VoiceModels.byId("missing").isEmpty());
    }
}
