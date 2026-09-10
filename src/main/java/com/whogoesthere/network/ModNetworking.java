package com.whogoesthere.network;

import com.whogoesthere.client.ClientPayloadHandler;
import com.whogoesthere.network.payload.HighlightRequestPayload;
import com.whogoesthere.network.payload.ScanRequestPayload;
import com.whogoesthere.network.payload.ScanResultPayload;
import com.whogoesthere.server.ServerPayloadHandler;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

/**
 * 三个包的登记处。在 mod 总线上监听 {@link RegisterPayloadHandlersEvent}，两端都会跑。
 *
 * <p>客户端逻辑本身放在 {@code com.whogoesthere.client.ClientPayloadHandler}，
 * 服务端逻辑放在 {@code com.whogoesthere.server.ServerPayloadHandler}，这里只做路由。</p>
 */
public final class ModNetworking {

    /** 网络版本号：两端不一致时 NeoForge 会直接拒绝连接，方便以后改协议时防呆。 */
    public static final String NETWORK_VERSION = "1";

    private ModNetworking() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(NETWORK_VERSION);

        // 客户端 -> 服务端
        registrar.playToServer(
                ScanRequestPayload.TYPE,
                ScanRequestPayload.STREAM_CODEC,
                ServerPayloadHandler::onScanRequest);
        registrar.playToServer(
                HighlightRequestPayload.TYPE,
                HighlightRequestPayload.STREAM_CODEC,
                ServerPayloadHandler::onHighlightRequest);

        // 服务端 -> 客户端
        registrar.playToClient(
                ScanResultPayload.TYPE,
                ScanResultPayload.STREAM_CODEC,
                ClientPayloadHandler::onScanResult);
    }
}
