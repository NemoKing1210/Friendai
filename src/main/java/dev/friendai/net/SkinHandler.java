package dev.friendai.net;

import dev.friendai.core.FriendaiServerState;
import dev.friendai.entity.CompanionEntity;
import dev.friendai.entity.CompanionManager;
import dev.friendai.features.appearance.SkinHash;
import dev.friendai.features.appearance.SkinImage;
import dev.friendai.foundation.limits.RateLimiter;
import java.util.Optional;
import java.util.function.BiConsumer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Server side of companion skins: stores an owner's upload and serves images to clients that ask by hash. */
public final class SkinHandler {
    private static volatile RateLimiter limiter = new RateLimiter(300, System::currentTimeMillis);
    private static volatile BiConsumer<ServerPlayer, SkinS2CPayload> sender = (player, payload) -> {
        if (ServerPlayNetworking.canSend(player, SkinS2CPayload.TYPE)) {
            ServerPlayNetworking.send(player, payload);
        }
    };

    private SkinHandler() {}

    /** Test hook: replace the rate limiter. */
    public static void setLimiter(RateLimiter replacement) {
        limiter = replacement;
    }

    /** Test hook: replace the way an image reaches a client. */
    public static void setSender(BiConsumer<ServerPlayer, SkinS2CPayload> replacement) {
        sender = replacement;
    }

    /** Sets the sender's companion skin, or resets it when the image is empty. Invalid images change nothing. */
    public static void upload(ServerPlayer player, SkinUploadC2SPayload payload) {
        if (!limiter.tryAcquire(player.getUUID())) {
            return;
        }
        var server = player.level().getServer();
        Optional<CompanionEntity> companion = CompanionManager.get(server).find(player.getUUID());
        if (companion.isEmpty()) {
            player.sendSystemMessage(Component.translatable("friendai.menu.no_companion"));
            return;
        }
        var skins = FriendaiServerState.get(server).skins();
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
    public static void request(ServerPlayer player, SkinRequestC2SPayload payload) {
        String hash = payload.hash();
        if (!SkinHash.isValid(hash) || !limiter.tryAcquire(player.getUUID())) {
            return;
        }
        var server = player.level().getServer();
        var skins = FriendaiServerState.get(server).skins();
        for (CompanionEntity companion : CompanionManager.get(server).all()) {
            if (!hash.equals(companion.getSkinHash()) || companion.ownerUuid() == null) {
                continue;
            }
            var stored = skins.load(companion.ownerUuid());
            if (stored.isPresent() && stored.get().hash().equals(hash)) {
                sender.accept(
                        player,
                        new SkinS2CPayload(
                                hash, stored.get().slim(), stored.get().png()));
                return;
            }
        }
    }
}
