package com.whogoesthere.server;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 发光定位：点一下名单里的名字，服务端就给那个实体打上发光标记，10 秒后自动收回。
 *
 * <p>到期时间靠 {@link ServerTickEvent.Post} 每 tick 检查，不依赖调度器。</p>
 */
public final class HighlightManager {

    /** 10 秒 = 200 tick。 */
    public static final int HIGHLIGHT_DURATION_TICKS = 200;

    private static final List<Highlight> ACTIVE = new ArrayList<>();

    private HighlightManager() {
    }

    /**
     * 给实体打标记；重复点同一个实体会刷新计时。
     *
     * <p>给 uuid + dimension 时按「跨维度」定位（置顶条目走这条路），
     * 否则用玩家当前维度里的网络 id。</p>
     */
    public static void apply(ServerPlayer player, int entityId, UUID uuid, ResourceLocation dimension) {
        ServerLevel level;
        Entity target;
        if (uuid != null) {
            MinecraftServer server = player.getServer();
            level = server == null || dimension == null
                    ? null
                    : server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
            target = level == null ? null : level.getEntity(uuid);
        } else {
            level = player.serverLevel();
            target = level.getEntity(entityId);
        }
        if (level == null || target == null) {
            player.displayClientMessage(Component.translatable("message.whogoesthere.gone"), true);
            return;
        }

        target.setGlowingTag(true);

        UUID targetUuid = target.getUUID();
        ResourceKey<Level> targetDimension = level.dimension();
        long expiresAt = level.getGameTime() + HIGHLIGHT_DURATION_TICKS;

        ACTIVE.removeIf(h -> h.uuid().equals(targetUuid) && h.dimension().equals(targetDimension));
        ACTIVE.add(new Highlight(targetDimension, targetUuid, expiresAt));
    }

    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        Iterator<Highlight> iterator = ACTIVE.iterator();
        while (iterator.hasNext()) {
            Highlight highlight = iterator.next();
            ServerLevel level = server.getLevel(highlight.dimension());
            if (level == null) {
                iterator.remove();
                continue;
            }
            if (level.getGameTime() >= highlight.expiresAt()) {
                Entity entity = level.getEntity(highlight.uuid());
                if (entity != null) {
                    entity.setGlowingTag(false);
                }
                iterator.remove();
            }
        }
    }

    /** 关服时清空，免得跨存档残留。 */
    public static void onServerStopped(ServerStoppedEvent event) {
        ACTIVE.clear();
    }

    private record Highlight(ResourceKey<Level> dimension, UUID uuid, long expiresAt) {
    }
}
