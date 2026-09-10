package com.whogoesthere.client;

import com.whogoesthere.network.payload.ScanResultPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 仅客户端：收到名单就开界面。
 *
 * <p>这个类是纯客户端的门口，服务端只会在 {@link com.whogoesthere.network.ModNetworking} 里
 * 引用它一次（方法引用，服务端执行路径永远不会走到）。</p>
 */
public final class ClientPayloadHandler {

    private ClientPayloadHandler() {
    }

    public static void onScanResult(ScanResultPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> Minecraft.getInstance().setScreen(new ScanScreen(payload.entries())));
    }
}
