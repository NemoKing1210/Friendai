package dev.friendai.knowledge;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

class KnowledgeFileMigrationTest {
    @Test
    void versionOneLoadsAsVersionTwoWithEmptyStats() {
        Gson gson = new Gson();
        String json = gson.toJson(new ResourceKnowledge(KnowledgeLimits.DEFAULTS).snapshot());
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        object.addProperty("version", 1);
        object.remove("searchStats");

        KnowledgeFile file = gson.fromJson(object, KnowledgeFile.class);
        assertDoesNotThrow(() -> file.migrate().validate(KnowledgeLimits.DEFAULTS));
        assertEquals(KnowledgeFile.CURRENT_VERSION, file.version());
        assertEquals(0, file.searchStats().size());
    }
}
