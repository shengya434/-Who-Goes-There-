package com.whogoesthere.network.payload;

import com.whogoesthere.WhoGoesThere;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S — 玩家在结果列表里点中了某一行，请服务端给那个实体打上发光标记（10 秒）。
 *
 * <p>两种定位方式：</p>
 * <ul>
 *   <li><b>常规条目</b>：给 {@code entityId}（网络 id），服务端在玩家当前维度里找；</li>
 *   <li><b>置顶条目</b>：给 {@code uuid} + {@code dimension} —— 它可能在别的维度，
 *       网络 id 出了本维度就没意义了。</li>
 * </ul>
 *
 * @param entityId  常规条目的网络 id；置顶条目为 0
 * @param uuid      置顶条目的实体 UUID；常规条目为 null
 * @param dimension 置顶条目的维度；常规条目为 null
 */
public record HighlightRequestPayload(int entityId, UUID uuid, ResourceLocation dimension)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<HighlightRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WhoGoesThere.MOD_ID, "highlight_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HighlightRequestPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, HighlightRequestPayload>() {
                @Override
                public HighlightRequestPayload decode(RegistryFriendlyByteBuf buf) {
                    int entityId = buf.readVarInt();
                    UUID uuid = buf.readBoolean() ? new UUID(buf.readLong(), buf.readLong()) : null;
                    ResourceLocation dimension = buf.readBoolean() ? buf.readResourceLocation() : null;
                    return new HighlightRequestPayload(entityId, uuid, dimension);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, HighlightRequestPayload payload) {
                    buf.writeVarInt(payload.entityId());
                    buf.writeBoolean(payload.uuid() != null);
                    if (payload.uuid() != null) {
                        buf.writeLong(payload.uuid().getMostSignificantBits());
                        buf.writeLong(payload.uuid().getLeastSignificantBits());
                    }
                    buf.writeBoolean(payload.dimension() != null);
                    if (payload.dimension() != null) {
                        buf.writeResourceLocation(payload.dimension());
                    }
                }
            };

    /** 常规条目：靠网络 id + 玩家当前维度定位。 */
    public static HighlightRequestPayload ofEntity(int entityId) {
        return new HighlightRequestPayload(entityId, null, null);
    }

    /** 置顶条目：靠 UUID + 维度定位。 */
    public static HighlightRequestPayload ofUuid(UUID uuid, ResourceLocation dimension) {
        return new HighlightRequestPayload(0, uuid, dimension);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
