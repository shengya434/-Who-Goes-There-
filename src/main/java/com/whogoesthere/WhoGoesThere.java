package com.whogoesthere;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

/**
 * Who Goes There? / 谁在那！
 *
 * A sentry's challenge shouted into the dark: ask, and the world answers back.
 * v0.1.0 — project skeleton.
 */
@Mod(WhoGoesThere.MOD_ID)
public class WhoGoesThere {

    public static final String MOD_ID = "whogoesthere";
    public static final Logger LOGGER = LogUtils.getLogger();

    public WhoGoesThere(IEventBus modEventBus, ModContainer modContainer) {
        LOGGER.info("[谁在那！] Who Goes There? loaded — the sentry is on duty.");
    }
}
