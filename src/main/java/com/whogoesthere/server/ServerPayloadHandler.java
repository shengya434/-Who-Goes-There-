package com.whogoesthere.server;

import com.whogoesthere.WhoGoesThere;
import com.whogoesthere.network.payload.HighlightRequestPayload;
import com.whogoesthere.network.payload.ScanRequestPayload;
import com.whogoesthere.network.payload.ScanResultPayload;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端收到 C2S 包之后的入口。
 */
public final class ServerPayloadHandler {

    private ServerPayloadHandler() {
    }

    public static void onScanRequest(ScanRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> {
            List<ScanResultPayload.EntityInfo> entries = ScanService.scan(player);
            WhoGoesThere.LOGGER.debug("[谁在那！] {} 发起了扫描，找到 {} 条结果（含聚合后的掉落物）",
                    player.getGameProfile().getName(), entries.size());
            context.reply(new ScanResultPayload(entries));
        });
    }

    public static void onHighlightRequest(HighlightRequestPayload payload, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) {
            return;
        }
        context.enqueueWork(() -> HighlightManager.apply(player, payload.entityId()));
    }
}
