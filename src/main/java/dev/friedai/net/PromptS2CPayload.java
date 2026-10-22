package dev.friedai.net;

import dev.friedai.FriedaiMod;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** The system prompt the companion's next LLM request would carry. */
public record PromptS2CPayload(String prompt) implements CustomPacketPayload {
    public static final int MAX_CHARS = 32767;
    public static final CustomPacketPayload.Type<PromptS2CPayload> TYPE =
            new CustomPacketPayload.Type<>(FriedaiMod.id("companion_prompt"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, PromptS2CPayload> CODEC =
            ByteBufCodecs.stringUtf8(MAX_CHARS).map(PromptS2CPayload::new, PromptS2CPayload::prompt).cast();

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
