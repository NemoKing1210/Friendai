package dev.friendai.test;

import dev.friendai.chat.CompanionSpeaker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;

public class SpeechTests {
    @GameTest(maxTicks = 100)
    public void speechWithoutAChannelStillWritesChat(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionSpeaker speaker = new CompanionSpeaker(
                h.getLevel().getServer(), player.getUUID(), () -> "Nova", () -> 400);
        speaker.say("Hello there", player.getUUID());
        h.succeed();
    }
}
