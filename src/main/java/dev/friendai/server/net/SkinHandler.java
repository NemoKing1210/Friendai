package dev.friendai.server.net;

import dev.friendai.entity.CompanionEntity;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import dev.friendai.server.runtime.ServerRuntime;
import dev.friendai.transport.payload.SkinRequestC2SPayload;
import dev.friendai.transport.payload.SkinS2CPayload;
import dev.friendai.transport.payload.SkinUploadC2SPayload;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of companion skins: stores an owner's upload and serves images to clients that ask by hash. */
public final class SkinHandler {
    private static volatile SkinSender sender = SkinHandler::send;

    private SkinHandler() {}

    /** Test hook: replace the way an image reaches a client. */
    public static void setSender(BiConsumer<ServerPlayer, SkinS2CPayload> replacement) {
        BiConsumer<ServerPlayer, SkinS2CPayload> testSender = Objects.requireNonNull(replacement, "replacement");
        sender = (runtime, player, payload) -> testSender.accept(player, payload);
    }

    /** Sets the sender's companion skin, or resets it when the image is empty. Invalid images change nothing. */
    public static void upload(ServerRuntime runtime, ServerPlayer player, SkinUploadC2SPayload payload) {
        if (!runtime.protocolConnections().skinLimiter().tryAcquire(player.getUUID())) {
            return;
        }
        Optional<CompanionEntity> companion = runtime.companions().find(player.getUUID());
        if (companion.isEmpty()) {
            player.sendSystemMessage(Component.translatable("friendai.menu.no_companion"));
            return;
        }
        var skins = runtime.skins();
        if (payload.png().length == 0) {
            skins.clear(player.getUUID());
            companion.get().setSkin("", false);
            return;
        }
        if (!(SkinImage.validate(payload.png()) instanceof SkinImage.Valid valid)) {
            player.sendSystemMessage(Component.translatable("friendai.skin.error.rejected"));
            return;
        }
        skins.save(player.getUUID(), valid.png(), payload.slim());
        companion.get().setSkin(SkinHash.of(valid.png()), payload.slim());
    }

    /** Answers with the image only when {@code hash} is the skin some loaded companion currently wears. */
    public static void request(ServerRuntime runtime, ServerPlayer player, SkinRequestC2SPayload payload) {
        String hash = payload.hash();
        if (!SkinHash.isValid(hash)
                || !runtime.protocolConnections().skinLimiter().tryAcquire(player.getUUID())) {
            return;
        }
        var skins = runtime.skins();
        for (CompanionEntity companion : runtime.companions().all()) {
            if (!hash.equals(companion.getSkinHash()) || companion.ownerUuid() == null) {
                continue;
            }
            var stored = skins.load(companion.ownerUuid());
            if (stored.isPresent() && stored.get().hash().equals(hash)) {
                sender.send(
                        runtime,
                        player,
                        new SkinS2CPayload(
                                hash, stored.get().slim(), stored.get().png()));
                return;
            }
        }
    }

    private static void send(ServerRuntime runtime, ServerPlayer player, SkinS2CPayload payload) {
        if (ServerPlayNetworking.canSend(player, SkinS2CPayload.TYPE)
                && runtime.protocolConnections().allowsAppearance(player)) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    @FunctionalInterface
    private interface SkinSender {
        void send(ServerRuntime runtime, ServerPlayer player, SkinS2CPayload payload);
    }
}
