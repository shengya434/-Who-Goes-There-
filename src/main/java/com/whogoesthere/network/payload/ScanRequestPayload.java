package com.whogoesthere.network.payload;

import com.whogoesthere.WhoGoesThere;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C2S — "谁在那！"：客户端请服务端扫描一次周边活物。
 *
 * <p>空包，只需要把意图送到服务端。</p>
 */
public record ScanRequestPayload() implements CustomPacketPayload {

    public static final ScanRequestPayload INSTANCE = new ScanRequestPayload();

    public static final CustomPacketPayload.Type<ScanRequestPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WhoGoesThere.MOD_ID, "scan_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanRequestPayload> STREAM_CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
