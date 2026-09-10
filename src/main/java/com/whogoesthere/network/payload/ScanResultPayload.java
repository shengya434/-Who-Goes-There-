package com.whogoesthere.network.payload;

import com.whogoesthere.WhoGoesThere;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S2C — 扫描结果：一份按距离升序排好的活物名单（最多 {@value #MAX_ENTRIES} 条）。
 */
public record ScanResultPayload(List<EntityInfo> entries) implements CustomPacketPayload {

    /** 服务端硬上限，防止一次性把几千个实体塞进一个包里。 */
    public static final int MAX_ENTRIES = 200;

    public static final CustomPacketPayload.Type<ScanResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WhoGoesThere.MOD_ID, "scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload>() {
                @Override
                public ScanResultPayload decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<EntityInfo> list = new ArrayList<>(Math.max(0, Math.min(size, MAX_ENTRIES)));
                    for (int i = 0; i < size; i++) {
                        list.add(new EntityInfo(
                                buf.readVarInt(),
                                buf.readResourceLocation(),
                                ComponentSerialization.STREAM_CODEC.decode(buf),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readDouble(),
                                buf.readResourceLocation()));
                    }
                    return new ScanResultPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScanResultPayload payload) {
                    List<EntityInfo> list = payload.entries();
                    buf.writeVarInt(list.size());
                    for (EntityInfo entry : list) {
                        buf.writeVarInt(entry.entityId());
                        buf.writeResourceLocation(entry.typeId());
                        ComponentSerialization.STREAM_CODEC.encode(buf, entry.name());
                        buf.writeDouble(entry.x());
                        buf.writeDouble(entry.y());
                        buf.writeDouble(entry.z());
                        buf.writeDouble(entry.distance());
                        buf.writeResourceLocation(entry.dimension());
                    }
                }
            };

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * 一条扫描记录。
     *
     * @param entityId  实体网络 id，点选定位时原样回传
     * @param typeId    实体类型 id（如 {@code minecraft:zombie}）
     * @param name      显示名（服务端 {@code getDisplayName()} 的结果，可带自定义名/队伍颜色）
     * @param distance  到发起者的距离，已保留 1 位小数
     * @param dimension 所在维度 id
     */
    public record EntityInfo(
            int entityId,
            ResourceLocation typeId,
            Component name,
            double x,
            double y,
            double z,
            double distance,
            ResourceLocation dimension) {
    }
}
