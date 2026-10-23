package dev.friendai.net;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.CompanionSnapshot;
import dev.friendai.debug.SnapshotCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The companion's current state for the menu. */
public record CompanionSnapshotS2CPayload(CompanionSnapshot snapshot) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CompanionSnapshotS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("companion_snapshot"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, CompanionSnapshotS2CPayload> CODEC =
            ByteBufCodecs.byteArray(1 << 20)
                    .map(bytes -> new CompanionSnapshotS2CPayload(SnapshotCodec.decode(bytes)),
                            p -> SnapshotCodec.encode(p.snapshot()))
                    .cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
