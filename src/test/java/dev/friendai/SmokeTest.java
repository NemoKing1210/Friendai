package dev.friendai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SmokeTest {
    @Test
    void modIdIsFriendai() {
        assertEquals("friendai", FriendaiMod.MOD_ID);
    }
}
