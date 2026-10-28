package dev.friendai.net;

import dev.friendai.FriendaiMod;
import dev.friendai.skill.SkillViewCodec;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

public record SkillsViewS2CPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<SkillsViewS2CPayload> TYPE = new Type<>(FriendaiMod.id("skills_view"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, SkillsViewS2CPayload> CODEC =
            ByteBufCodecs.byteArray(SkillViewCodec.MAX_BYTES)
                    .map(SkillsViewS2CPayload::new, SkillsViewS2CPayload::data)
                    .cast();

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
