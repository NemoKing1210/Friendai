package dev.friedai.net;

import dev.friedai.FriedaiMod;
import dev.friedai.debug.CompanionSnapshot;
import dev.friedai.debug.SnapshotCodec;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The companion's current state for the menu. */
public record CompanionSnapshotS2CPayload(CompanionSnapshot snapshot) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CompanionSnapshotS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriedaiMod.id("companion_snapshot"));
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
