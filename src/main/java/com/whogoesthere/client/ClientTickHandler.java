package com.whogoesthere.client;

import com.whogoesthere.network.payload.ScanRequestPayload;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 仅客户端：每 tick 检查一次按键，按下就向服务端要一份名单。
 */
public final class ClientTickHandler {

    private ClientTickHandler() {
    }

    public static void onClientTick(ClientTickEvent.Post event) {
        boolean pressed = false;
        while (ClientKeyMappings.OPEN_SCANNER.consumeClick()) {
            pressed = true;
        }
        if (!pressed) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            return;
        }

        PacketDistributor.sendToServer(ScanRequestPayload.INSTANCE);
    }
}
