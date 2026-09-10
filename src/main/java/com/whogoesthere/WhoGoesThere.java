package com.whogoesthere;

import com.mojang.logging.LogUtils;
import com.whogoesthere.network.ModNetworking;
import com.whogoesthere.server.HighlightManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

/**
 * Who Goes There? / 谁在那！
 *
 * A sentry's challenge shouted into the dark: ask, and the world answers back.
 * v0.1.0 — press V, get a list of everything alive around you.
 */
@Mod(WhoGoesThere.MOD_ID)
public class WhoGoesThere {

    public static final String MOD_ID = "whogoesthere";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WhoGoesThere(IEventBus modEventBus, ModContainer modContainer) {
        // --- 两端都跑的部分 ---------------------------------------------
        // 网络包登记（mod 总线）
        modEventBus.addListener(ModNetworking::register);
        // 发光标记 10 秒到期的计时
        NeoForge.EVENT_BUS.addListener(HighlightManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(HighlightManager::onServerStopped);

        // --- 仅客户端的部分 ---------------------------------------------
        // 这里用 dist 判断包起来：客户端专有类只在客户端被加载，
        // 专有服上这两行代码根本不会执行到。
        if (FMLEnvironment.dist.isClient()) {
            // 按键登记 -> 自动出现在「选项 → 控制」里
            modEventBus.addListener(com.whogoesthere.client.ClientKeyMappings::registerKeyMappings);
            // 每 tick 吃掉一次按键 -> 发扫描请求
            NeoForge.EVENT_BUS.addListener(com.whogoesthere.client.ClientTickHandler::onClientTick);
        }

        LOGGER.info("[谁在那！] Who Goes There? loaded — the sentry is on duty. (dist={})", FMLEnvironment.dist);
    }
}
