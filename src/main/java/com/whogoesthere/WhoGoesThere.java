package com.whogoesthere;

import com.mojang.logging.LogUtils;
import com.whogoesthere.network.ModNetworking;
import com.whogoesthere.server.HighlightManager;
import com.whogoesthere.server.StampManager;
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
 * v0.2.0 — press V, get a list of everything alive (and everything dropped) around you,
 * grouped by mod, clickable straight to a teleport.
 */
@Mod(WhoGoesThere.MOD_ID)
public class WhoGoesThere {

    public static final String MOD_ID = "whogoesthere";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WhoGoesThere(IEventBus modEventBus, ModContainer modContainer) {
        // --- 两端都跑的部分 ---------------------------------------------
        // 网络包登记（mod 总线）
        modEventBus.addListener(ModNetworking::register);
        // 物品 / 实体数据附件登记（mod 总线）
        ModItems.ITEMS.register(modEventBus);
        ModAttachments.ATTACHMENT_TYPES.register(modEventBus);
        modEventBus.addListener(ModItems::addCreativeTabItems);
        // 发光标记 10 秒到期的计时
        NeoForge.EVENT_BUS.addListener(HighlightManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(HighlightManager::onServerStopped);
        // 印章：开服重建强制加载、每秒跟随移动、死亡清理
        NeoForge.EVENT_BUS.addListener(StampManager::onServerStarted);
        NeoForge.EVENT_BUS.addListener(StampManager::onServerTick);
        NeoForge.EVENT_BUS.addListener(StampManager::onLivingDeath);

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
