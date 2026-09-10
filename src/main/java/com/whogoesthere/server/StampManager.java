package com.whogoesthere.server;

import com.whogoesthere.ModAttachments;
import com.whogoesthere.WhoGoesThere;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * 「印章」机制的心脏：盖章 / 擦除 / 永加载 / 死亡清理。
 *
 * <p>三件事绑在一起：</p>
 * <ol>
 *   <li><b>不消失</b> —— {@link Mob#setPersistenceRequired(boolean)}（原版机制，比 mixin 稳）；</li>
 *   <li><b>永加载</b> —— {@link ServerLevel#setChunkForced(int, int, boolean)}，并每 20 tick
 *       核对一次实体的实际位置，把强制加载从旧区块挪到新区块（跨维度也认）；</li>
 *   <li><b>登记</b> —— {@link StampRegistry} 记 UUID + 维度 + 坐标，供扫描置顶。</li>
 * </ol>
 *
 * <p><b>性能提醒</b>：每个盖章目标会常驻加载 1 个区块。章盖多了（几十个）会明显增加
 * 常驻 tick 量，这是原版强制加载的固有代价，不是本模组能绕开的。</p>
 */
public final class StampManager {

    /** 每 20 tick（1 秒）核对一次所有盖章实体。 */
    private static final int RECONCILE_INTERVAL = 20;
    /**
     * 「找不到实体」容错上限（tick）。累计超过这个数才从登记表除名。
     * 默认 6000 tick = 5 分钟 —— 区块加载/跨维度传送的间隙足够长，不会误删；
     * 真正死亡走 {@link #onLivingDeath} 立即清理，不等这里。
     */
    private static final int MISS_GRACE_TICKS = 6000;
    /** 坐标变化小于这个距离就不写登记表，省点磁盘。 */
    private static final double POSITION_EPSILON = 0.5D;

    /** 当前替每个 UUID 强制加载的区块（不是登记表；登记表只管「谁」，这里管「压在哪个区块」）。 */
    private static final Map<UUID, ChunkRef> FORCED = new HashMap<>();
    /** UUID -> 连续找不到的累计 tick 数。 */
    private static final Map<UUID, Integer> MISS_TICKS = new HashMap<>();
    private static int tickCounter;

    private StampManager() {
    }

    // ------------------------------------------------------------------
    // 盖章 / 擦除
    // ------------------------------------------------------------------

    /** 右键生物盖一个章。重复盖给提示，不重复生效。 */
    public static void stamp(ServerPlayer player, LivingEntity target) {
        if (target instanceof Player) {
            player.displayClientMessage(Component.translatable("message.whogoesthere.stamp.player"), true);
            return;
        }
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        StampRegistry registry = StampRegistry.get(server);
        UUID uuid = target.getUUID();
        if (registry.contains(uuid) || isMarked(target)) {
            player.displayClientMessage(Component.translatable("message.whogoesthere.stamp.already"), true);
            return;
        }

        target.setData(ModAttachments.STAMPED, Boolean.TRUE);
        ResourceLocation dimension = target.level().dimension().location();
        double x = target.getX();
        double y = target.getY();
        double z = target.getZ();
        registry.put(uuid, dimension, x, y, z);

        if (target instanceof Mob mob) {
            mob.setPersistenceRequired();
        }
        forceChunk(server, uuid, dimension, new ChunkPos(target.blockPosition()));
        MISS_TICKS.remove(uuid);

        player.displayClientMessage(Component.translatable("message.whogoesthere.stamp.ok", target.getDisplayName()), true);
        WhoGoesThere.LOGGER.info("[谁在那！] {} 盖了章：{} @ {} [{}, {}, {}]（UUID {}）",
                player.getGameProfile().getName(), target.getDisplayName().getString(), dimension, x, y, z, uuid);
    }

    /** 橡皮擦：移除印章的全部效果。没盖过章就给个提示。 */
    public static void erase(ServerPlayer player, LivingEntity target) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        StampRegistry registry = StampRegistry.get(server);
        UUID uuid = target.getUUID();
        if (!registry.contains(uuid) && !isMarked(target)) {
            player.displayClientMessage(Component.translatable("message.whogoesthere.erase.none"), true);
            return;
        }

        target.setData(ModAttachments.STAMPED, Boolean.FALSE);
        registry.remove(uuid);
        unforce(server, uuid);
        MISS_TICKS.remove(uuid);
        if (target instanceof Mob mob) {
            // 1.21.1 的 setPersistenceRequired() 只有「设为 true」一个版本，想要设回 false
            // 只能直接写字段 —— 靠 accesstransformer.cfg 把这个 private 字段放宽为 public。
            mob.persistenceRequired = false;
        }

        player.displayClientMessage(Component.translatable("message.whogoesthere.erase.ok", target.getDisplayName()), true);
        WhoGoesThere.LOGGER.info("[谁在那！] {} 擦除了 {} 的印章（UUID {}）",
                player.getGameProfile().getName(), target.getDisplayName().getString(), uuid);
    }

    private static boolean isMarked(LivingEntity target) {
        return Boolean.TRUE.equals(target.getData(ModAttachments.STAMPED));
    }

    // ------------------------------------------------------------------
    // 事件
    // ------------------------------------------------------------------

    /** 开服：把登记表里的目标重新压上强制加载（原版也会持久化强制区块，这里再保险一次）。 */
    public static void onServerStarted(ServerStartedEvent event) {
        MinecraftServer server = event.getServer();
        StampRegistry registry = StampRegistry.get(server);
        FORCED.clear();
        MISS_TICKS.clear();
        tickCounter = 0;
        for (UUID uuid : new ArrayList<>(registry.entryIds())) {
            StampRegistry.Entry entry = registry.get(uuid);
            if (entry == null) {
                continue;
            }
            forceChunk(server, uuid, entry.dimension(), chunkOf(entry.x(), entry.z()));
        }
        WhoGoesThere.LOGGER.info("[谁在那！] 印章登记表载入：{} 个永加载目标", registry.size());
    }

    /** 实体死了 → 印章跟着走（登记表除名 + 取消强制加载）。 */
    public static void onLivingDeath(LivingDeathEvent event) {
        LivingEntity entity = event.getEntity();
        MinecraftServer server = entity.getServer();
        if (server == null) {
            return;
        }
        UUID uuid = entity.getUUID();
        StampRegistry registry = StampRegistry.get(server);
        if (registry.contains(uuid)) {
            registry.remove(uuid);
            unforce(server, uuid);
            MISS_TICKS.remove(uuid);
            WhoGoesThere.LOGGER.info("[谁在那！] 盖章目标已死亡，效力随之消失（UUID {}）", uuid);
        }
    }

    /** 每秒核对一次：跟随移动换区块、刷新不掉落标记，顺手清理早已不存在的登记。 */
    public static void onServerTick(ServerTickEvent.Post event) {
        if (++tickCounter % RECONCILE_INTERVAL != 0) {
            return;
        }
        MinecraftServer server = event.getServer();
        StampRegistry registry = StampRegistry.get(server);
        if (registry.size() == 0 && FORCED.isEmpty()) {
            return;
        }

        for (UUID uuid : new ArrayList<>(registry.entryIds())) {
            StampRegistry.Entry entry = registry.get(uuid);
            if (entry == null) {
                continue;
            }
            Entity entity = resolve(server, entry.dimension(), uuid);
            ResourceLocation dimension = entry.dimension();
            ChunkPos chunk;
            if (entity != null) {
                dimension = entity.level().dimension().location();
                // 位置/维度变了才写盘，别每秒钟都抖一次
                if (!dimension.equals(entry.dimension())
                        || Math.abs(entity.getX() - entry.x()) > POSITION_EPSILON
                        || Math.abs(entity.getY() - entry.y()) > POSITION_EPSILON
                        || Math.abs(entity.getZ() - entry.z()) > POSITION_EPSILON) {
                    registry.update(uuid, dimension, entity.getX(), entity.getY(), entity.getZ());
                }
                if (entity instanceof Mob mob) {
                    mob.setPersistenceRequired(); // 有些机制会把它重置，定期压回去
                }
                chunk = new ChunkPos(entity.blockPosition());
                MISS_TICKS.remove(uuid);
            } else {
                int missed = MISS_TICKS.merge(uuid, RECONCILE_INTERVAL, Integer::sum);
                if (missed > MISS_GRACE_TICKS) {
                    WhoGoesThere.LOGGER.info("[谁在那！] 盖章目标长时间找不到，从登记表除名（UUID {}）", uuid);
                    registry.remove(uuid);
                    MISS_TICKS.remove(uuid);
                    unforce(server, uuid);
                    continue;
                }
                chunk = chunkOf(entry.x(), entry.z());
            }
            forceChunk(server, uuid, dimension, chunk);
        }

        // 登记表里已经没有的 UUID，但这边还压着区块 —— 收掉，别漏
        for (UUID uuid : new ArrayList<>(FORCED.keySet())) {
            if (!registry.contains(uuid)) {
                unforce(server, uuid);
            }
        }
    }

    // ------------------------------------------------------------------
    // 内部工具
    // ------------------------------------------------------------------

    /** 在指定维度按 UUID 找实体；维度不存在或实体未加载都返回 null。 */
    private static Entity resolve(MinecraftServer server, ResourceLocation dimension, UUID uuid) {
        ServerLevel level = level(server, dimension);
        return level == null ? null : level.getEntity(uuid);
    }

    private static ServerLevel level(MinecraftServer server, ResourceLocation dimension) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
    }

    private static ChunkPos chunkOf(double x, double z) {
        return new ChunkPos((int) Math.floor(x) >> 4, (int) Math.floor(z) >> 4);
    }

    /** 确保 uuid 压住的正是 (dimension, chunk) 这块；换了地方就先松开旧的。 */
    private static void forceChunk(MinecraftServer server, UUID uuid, ResourceLocation dimension, ChunkPos chunk) {
        ChunkRef current = FORCED.get(uuid);
        if (current != null && current.dimension().equals(dimension)
                && current.x() == chunk.x && current.z() == chunk.z) {
            return;
        }
        if (current != null) {
            unforce(server, uuid);
        }
        ServerLevel level = level(server, dimension);
        if (level == null) {
            return;
        }
        level.setChunkForced(chunk.x, chunk.z, true);
        FORCED.put(uuid, new ChunkRef(dimension, chunk.x, chunk.z));
    }

    /** 松开 uuid 压着的区块（没有就什么都不做）。 */
    private static void unforce(MinecraftServer server, UUID uuid) {
        ChunkRef ref = FORCED.remove(uuid);
        if (ref == null) {
            return;
        }
        ServerLevel level = level(server, ref.dimension());
        if (level != null) {
            level.setChunkForced(ref.x(), ref.z(), false);
        }
    }

    private record ChunkRef(ResourceLocation dimension, int x, int z) {
    }

}
