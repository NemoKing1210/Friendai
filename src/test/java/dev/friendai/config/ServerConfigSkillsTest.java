package dev.friendai.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

class ServerConfigSkillsTest {
    @Test
    void legacyDescriptionMigratesAndNewValueWins() {
        var old = JsonParser.parseString("{\"companion\":{\"persona\":\"Old guide\"}}")
                .getAsJsonObject();
        assertEquals(
                "Old guide",
                ServerConfig.migrate(old, 12)
                        .getAsJsonObject("companion")
                        .get("description")
                        .getAsString());
        old.getAsJsonObject("companion").addProperty("description", "New guide");
        assertEquals(
                "New guide",
                ServerConfig.migrate(old, 12)
                        .getAsJsonObject("companion")
                        .get("description")
                        .getAsString());
        assertFalse(old.getAsJsonObject("companion").has("persona"));
    }

    @Test
    void writeAndPromptBudgetsAreBounded() {
        var c = ServerConfig.defaults();
        c.skills.promptBudget = 100_000;
        c.skills.modelWritesPerHour = -1;
        c.skills.writesPerOutcome = 100;
        c.validate();
        assertEquals(6000, c.skills.promptBudget);
        assertEquals(0, c.skills.modelWritesPerHour);
        assertEquals(2, c.skills.writesPerOutcome);
    }
}
