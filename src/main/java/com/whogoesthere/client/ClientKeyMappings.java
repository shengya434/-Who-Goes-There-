package com.whogoesthere.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

/**
 * 仅客户端：按键定义。默认 V，分类 {@code key.categories.whogoesthere}，
 * 注册之后会自动出现在「选项 → 控制…」里，玩家可以自己改键。
 */
public final class ClientKeyMappings {

    public static final String CATEGORY = "key.categories.whogoesthere";

    public static final KeyMapping OPEN_SCANNER =
            new KeyMapping("key.whogoesthere.open", InputConstants.KEY_V, CATEGORY);

    private ClientKeyMappings() {
    }

    /** 在 mod 总线上监听 {@link RegisterKeyMappingsEvent}（该事件只在物理客户端触发）。 */
    public static void registerKeyMappings(RegisterKeyMappingsEvent event) {
        event.register(OPEN_SCANNER);
    }
}
