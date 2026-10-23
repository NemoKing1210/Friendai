package dev.friendai.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StopScopeTest {
    @Test
    void blankOrMissingMeansCurrent() {
        assertEquals(new StopScope.Current(), StopScope.parse(null));
        assertEquals(new StopScope.Current(), StopScope.parse(""));
        assertEquals(new StopScope.Current(), StopScope.parse("  "));
    }

    @Test
    void currentKeywordIsCaseAndSpaceInsensitive() {
        assertEquals(new StopScope.Current(), StopScope.parse("current"));
        assertEquals(new StopScope.Current(), StopScope.parse(" Current "));
    }

    @Test
    void allAndEverythingMeanAll() {
        assertEquals(new StopScope.All(), StopScope.parse("all"));
        assertEquals(new StopScope.All(), StopScope.parse("EVERYTHING"));
    }

    @Test
    void anythingElseIsATaskName() {
        assertEquals(new StopScope.Named("mine_blocks"), StopScope.parse("mine_blocks"));
        assertEquals(new StopScope.Named("mine_blocks"), StopScope.parse(" Mine_Blocks "));
    }
}
