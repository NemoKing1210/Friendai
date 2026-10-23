package dev.friendai.net;

import dev.friendai.FriendaiMod;
import dev.friendai.debug.RequestType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** A request from the companion menu. {@code a} and {@code b} depend on the type (message text, tool name and JSON arguments). */
public record CompanionRequestC2SPayload(RequestType request, String a, String b) implements CustomPacketPayload {
    public static final int MAX_A = 500;
    public static final int MAX_B = 2000;
    public static final CustomPacketPayload.Type<CompanionRequestC2SPayload> TYPE =
            new CustomPacketPayload.Type<>(FriendaiMod.id("companion_request"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, CompanionRequestC2SPayload> CODEC =
            StreamCodec.composite(
                            ByteBufCodecs.VAR_INT, (CompanionRequestC2SPayload p) -> p.request().ordinal(),
                            ByteBufCodecs.stringUtf8(MAX_A), CompanionRequestC2SPayload::a,
                            ByteBufCodecs.stringUtf8(MAX_B), CompanionRequestC2SPayload::b,
                            (ordinal, a, b) -> new CompanionRequestC2SPayload(RequestType.fromOrdinal(ordinal), a, b))
                    .cast();

    public CompanionRequestC2SPayload(RequestType request) {
        this(request, "", "");
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
