package com.whogoesthere.network.payload;

import com.whogoesthere.WhoGoesThere;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S — 玩家在结果列表里点中了某一行，请服务端给那个实体打上发光标记（10 秒）。
 *
 * @param entityId 目标实体的网络 id（{@code Entity#getId()}）
 */
public record HighlightRequestPayload(int entityId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HighlightRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WhoGoesThere.MOD_ID, "highlight_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HighlightRequestPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, HighlightRequestPayload>() {
                @Override
                public HighlightRequestPayload decode(RegistryFriendlyByteBuf buf) {
                    return new HighlightRequestPayload(buf.readVarInt());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, HighlightRequestPayload payload) {
                    buf.writeVarInt(payload.entityId());
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
