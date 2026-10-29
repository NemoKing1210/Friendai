package dev.friendai.test;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.FriendaiEntities;
import dev.friendai.features.conversation.NameColor;
import dev.friendai.protocol.DecodeResult;
import dev.friendai.protocol.ProtocolView;
import dev.friendai.protocol.speech.CompanionSpeechProtocolViewCodec;
import dev.friendai.protocol.speech.CompanionSpeechView;
import dev.friendai.server.agent.chat.CompanionSpeaker;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public class SpeechTests {
    @GameTest(maxTicks = 100)
    public void speechWithoutAChannelStillWritesChat(GameTestHelper h) {
        ServerPlayer player = GameTestSupport.mockPlayerAt(h, 2, 2, 2);
        UUID companionId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        CompanionSpeaker speaker = new CompanionSpeaker(
                h.getLevel().getServer(),
                player.getUUID(),
                () -> "Nova",
                () -> 400,
                thinking -> {},
                () -> companionId,
                () -> 16);
        speaker.say("Hello there", player.getUUID());
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void speechPayloadRoundTrips(GameTestHelper h) {
        UUID companionId = UUID.fromString("00000000-0000-0000-0000-0000000000c1");
        UUID addressee = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
        CompanionSpeechView view = new CompanionSpeechView("Nova", "Hello there", addressee, companionId, 16);
        var decoded = CompanionSpeechProtocolViewCodec.decode(CompanionSpeechProtocolViewCodec.encode(view, 7));
        h.assertTrue(decoded instanceof DecodeResult.Valid<?>, "speech view should decode");
        var received = ((DecodeResult.Valid<ProtocolView<CompanionSpeechView>>) decoded).value();
        h.assertValueEqual(7L, received.requestId(), "correlation id");
        h.assertValueEqual("Nova", received.value().companion(), "companion");
        h.assertValueEqual("Hello there", received.value().text(), "text");
        h.assertValueEqual(addressee, received.value().addressee(), "addressee");
        h.assertValueEqual(companionId, received.value().companionId(), "companion id");
        h.assertValueEqual(16, received.value().audibleRange(), "range");
        h.succeed();
    }

    @GameTest(maxTicks = 100)
    public void chatLineColoursOnlyTheName(GameTestHelper h) {
        Component line = CompanionSpeaker.chatLine("Nova", "Hello there");
        h.assertValueEqual("<Nova> Hello there", line.getString(), "chat text");
        Component name = line.getSiblings().get(0);
        h.assertValueEqual(NameColor.rgb("Nova"), name.getStyle().getColor().getValue(), "name colour");
        h.assertTrue(
                line.getSiblings().get(1).getStyle().getColor() == null,
                "the reply itself must keep the default colour");
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
