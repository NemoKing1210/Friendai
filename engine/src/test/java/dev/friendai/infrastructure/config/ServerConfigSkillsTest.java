package dev.friendai.infrastructure.config;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ServerConfigSkillsTest {

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
