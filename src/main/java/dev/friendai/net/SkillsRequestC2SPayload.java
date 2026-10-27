package dev.friendai.net;
import dev.friendai.FriendaiMod;
import net.minecraft.network.codec.*;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
/** Separate cap keeps unrelated menu packets small while supporting escaped long bodies. */
public record SkillsRequestC2SPayload(String operation,String json) implements CustomPacketPayload {
    public static final int MAX_JSON_CHARS=32768, MAX_OPERATION_CHARS=32;
    public static final Type<SkillsRequestC2SPayload> TYPE=new Type<>(FriendaiMod.id("skills_request"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf,SkillsRequestC2SPayload> CODEC=
            StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_OPERATION_CHARS),SkillsRequestC2SPayload::operation,
                    ByteBufCodecs.stringUtf8(MAX_JSON_CHARS),SkillsRequestC2SPayload::json,SkillsRequestC2SPayload::new).cast();
    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
