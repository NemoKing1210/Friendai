package dev.friendai.test;

import dev.friendai.chat.CompanionSpeaker;
import dev.friendai.chat.NameColor;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SpeechTests {
    @GameTest(maxTicks = 100)
    public void speechWithoutAChannelStillWritesChat(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        CompanionSpeaker speaker = new CompanionSpeaker(
                h.getLevel().getServer(), player.getUUID(), () -> "Nova", () -> 400, thinking -> {});
        speaker.say("Hello there", player.getUUID());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void chatLineColoursOnlyTheName(GameTestHelper h) {
        Component line = CompanionSpeaker.chatLine("Nova", "Hello there");
        h.assertValueEqual("<Nova> Hello there", line.getString(), "chat text");
        Component name = line.getSiblings().get(0);
        h.assertValueEqual(NameColor.rgb("Nova"), name.getStyle().getColor().getValue(), "name colour");
        h.assertTrue(
                line.getSiblings().get(1).getStyle().getColor() == null, "the reply itself must keep the default colour");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void renamingTheCompanionRecoloursItsNameTag(GameTestHelper h) {
        CompanionEntity companion = h.spawn(FriendaiEntities.COMPANION, 2, 2, 2);
        companion.applyName("Nova");
        h.assertTrue(companion.getCustomName() != null, "name tag is missing");
        h.assertValueEqual(
                NameColor.rgb("Nova"),
                companion.getCustomName().getStyle().getColor().getValue(),
                "name tag colour");
        companion.applyName("Ember");
        h.assertValueEqual(
                NameColor.rgb("Ember"),
                companion.getCustomName().getStyle().getColor().getValue(),
                "name tag colour after a rename");
        h.succeed();
    }
}
