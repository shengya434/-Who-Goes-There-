package com.whogoesthere.server;

import com.whogoesthere.network.payload.ScanResultPayload;
import com.whogoesthere.network.payload.ScanResultPayload.EntityInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 扫描本体：把「盖了章的置顶目标」和「发起者所在维度里所有已加载的实体」找出来。
 *
 * <p>v0.2 起收集两块：置顶段（登记表里盖章的，跨维度找）+ 常规段（活物一格一条、
 * 掉落物按「物品类型 + 空间簇」聚合）。</p>
 */
public final class ScanService {

    /** 找不到实体时，置顶条目用这个占位注册名 —— 免得界面拿到 null。 */
    private static final ResourceLocation UNKNOWN_TYPE =
            ResourceLocation.fromNamespaceAndPath("whogoesthere", "stamped");

    /**
     * 掉落物分簇半径（格）：彼此在这个距离以内才算「同一堆」。
     *
     * <p>取 8 格的理由：掉落物落地后会轻微散开（几格外），8 格足够把「同一处丢的一堆」
     * 收进同一簇；而玩家在不同地点各丢一堆时，两堆相隔通常远大于 8 格，会自然拆开。</p>
     */
    private static final double ITEM_CLUSTER_RADIUS = 8.0D;

    private ScanService() {
    }

    public static List<EntityInfo> scan(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ResourceLocation dimension = level.dimension().location();

        double originX = player.getX();
        double originY = player.getY();
        double originZ = player.getZ();
        int selfId = player.getId();

        List<EntityInfo> found = new ArrayList<>();
        // 物品 id -> 该物品在本维度里的全部掉落物（收集完再按空间邻近度分簇，见 clusterItemDrops）
        Map<ResourceLocation, List<ItemEntity>> itemDrops = new HashMap<>();

        // ---- 置顶段：登记表里盖了章的，跨维度找齐 ----
        List<EntityInfo> pinned = new ArrayList<>();
        Set<UUID> pinnedIds = new HashSet<>();
        MinecraftServer server = player.getServer();
        if (server != null) {
            StampRegistry registry = StampRegistry.get(server);
            for (UUID uuid : new ArrayList<>(registry.entryIds())) {
                StampRegistry.Entry entry = registry.get(uuid);
                if (entry == null) {
                    continue;
                }
                Entity entity = resolve(server, entry.dimension(), uuid);
                ResourceLocation entryDimension = entry.dimension();
                double x = entry.x();
                double y = entry.y();
                double z = entry.z();
                String namespace;
                ResourceLocation typeId;
                Component name;
                int entityId;
                if (entity != null) {
                    entryDimension = entity.level().dimension().location();
                    x = entity.getX();
                    y = entity.getY();
                    z = entity.getZ();
                    typeId = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                    namespace = typeId.getNamespace();
                    name = entity.getDisplayName();
                    entityId = entity.getId();
                } else {
                    typeId = UNKNOWN_TYPE;
                    namespace = UNKNOWN_TYPE.getNamespace();
                    name = Component.translatable("gui.whogoesthere.pinned.unknown");
                    entityId = 0;
                }
                double distance;
                if (entryDimension.equals(dimension)) {
                    double dx = x - originX;
                    double dy = y - originY;
                    double dz = z - originZ;
                    distance = round1(Math.sqrt(dx * dx + dy * dy + dz * dz));
                } else {
                    distance = -1.0D; // 异维度：距离没有意义，界面显示「异界」
                }
                pinned.add(ScanResultPayload.pinned(namespace, entityId, typeId, name, x, y, z, distance,
                        entryDimension, uuid));
                pinnedIds.add(uuid);
            }
        }

        // ---- 常规段：本维度的活物 + 聚合掉落物 ----
        for (Entity entity : level.getAllEntities()) {
            if (entity.getId() == selfId || entity == player) {
                continue; // 别把自己也报出来
            }
            if (pinnedIds.contains(entity.getUUID())) {
                continue; // 已经作为置顶条目报过了，别重复
            }

            double dx = entity.getX() - originX;
            double dy = entity.getY() - originY;
            double dz = entity.getZ() - originZ;
            double distance = round1(Math.sqrt(dx * dx + dy * dy + dz * dz));

            if (entity instanceof LivingEntity living) {
                ResourceLocation typeId = BuiltInRegistries.ENTITY_TYPE.getKey(living.getType());
                found.add(ScanResultPayload.living(
                        typeId.getNamespace(),
                        living.getId(),
                        typeId,
                        living.getDisplayName(),
                        living.getX(),
                        living.getY(),
                        living.getZ(),
                        distance,
                        dimension));
            } else if (entity instanceof ItemEntity itemEntity) {
                ItemStack stack = itemEntity.getItem();
                if (stack.isEmpty()) {
                    continue; // 空 stack 的掉落物（理论上不该有）跳过，免得包序列化炸掉
                }
                ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
                itemDrops.computeIfAbsent(itemId, key -> new ArrayList<>()).add(itemEntity);
            }
        }

        for (Map.Entry<ResourceLocation, List<ItemEntity>> group : itemDrops.entrySet()) {
            for (ItemAggregate aggregate : clusterItemDrops(group.getKey(), group.getValue(),
                    originX, originY, originZ)) {
                found.add(ScanResultPayload.item(
                        aggregate.itemId().getNamespace(),
                        aggregate.nearestId(),
                        aggregate.itemId(),
                        aggregate.name(),
                        aggregate.x(),
                        aggregate.y(),
                        aggregate.z(),
                        aggregate.distance(),
                        dimension,
                        aggregate.count(),
                        aggregate.sample()));
            }
        }

        found.sort(Comparator.comparingDouble(EntityInfo::distance));

        // 置顶段永远在最前；上限不够时优先保置顶
        List<EntityInfo> result = new ArrayList<>(pinned.size() + found.size());
        result.addAll(pinned);
        result.addAll(found);
        if (result.size() > ScanResultPayload.MAX_ENTRIES) {
            return new ArrayList<>(result.subList(0, ScanResultPayload.MAX_ENTRIES));
        }
        return result;
    }

    /** 在指定维度按 UUID 找实体；维度不存在或实体未加载都返回 null。 */
    private static Entity resolve(MinecraftServer server, ResourceLocation dimension, UUID uuid) {
        ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, dimension));
        return level == null ? null : level.getEntity(uuid);
    }

    /** 保留 1 位小数。 */
    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }

    /** 实体到某点的直线距离（原始值，不四舍五入）。 */
    private static double distanceTo(Entity entity, double x, double y, double z) {
        double dx = entity.getX() - x;
        double dy = entity.getY() - y;
        double dz = entity.getZ() - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 把同一种物品的掉落物按「空间邻近度」分簇，每簇产出一条聚合记录。
     *
     * <p>为什么聚合键是「物品 id + 空间簇」而不是纯物品 id：同一种物品经常散落在相隔很远的
     * 好几处（例如玩家在 A 点丢一堆、跑到 B 点又丢一堆）。若只按物品 id 合并成一行，列表里
     * 只会报「最近那一份」的坐标，玩家照着那个坐标过去只能找到一堆，其余几堆根本看不到，
     * 于是就有了「显示总量、却只报一处坐标」的查找困扰。按空间分簇后，相隔超过
     * {@value #ITEM_CLUSTER_RADIUS} 格的各成一行、各报各自坐标，才能真正找齐。</p>
     *
     * <p>算法：先按「到发起者的距离」升序排列，再贪心归簇 —— 与某个已有簇中心距离不超过
     * {@value #ITEM_CLUSTER_RADIUS} 格就并入其中最近的那个簇，否则新开一簇。因为按距离升序处理，
     * 每簇第一个加入的成员天然就是「离发起者最近的那一份」，用它做定位/发光最合适。</p>
     */
    private static List<ItemAggregate> clusterItemDrops(ResourceLocation itemId, List<ItemEntity> drops,
                                                        double originX, double originY, double originZ) {
        drops.sort(Comparator.comparingDouble(drop -> distanceTo(drop, originX, originY, originZ)));

        List<DropCluster> clusters = new ArrayList<>();
        for (ItemEntity drop : drops) {
            DropCluster nearest = null;
            double nearestGap = Double.MAX_VALUE;
            for (DropCluster cluster : clusters) {
                double gap = cluster.gapToCenter(drop.getX(), drop.getY(), drop.getZ());
                if (gap <= ITEM_CLUSTER_RADIUS && gap < nearestGap) {
                    nearestGap = gap;
                    nearest = cluster;
                }
            }
            if (nearest == null) {
                clusters.add(new DropCluster(drop));
            } else {
                nearest.add(drop);
            }
        }

        List<ItemAggregate> aggregates = new ArrayList<>(clusters.size());
        for (DropCluster cluster : clusters) {
            ItemStack stack = cluster.nearest.getItem();
            aggregates.add(new ItemAggregate(itemId, cluster.nearest.getId(), cluster.nearest.getX(),
                    cluster.nearest.getY(), cluster.nearest.getZ(),
                    round1(distanceTo(cluster.nearest, originX, originY, originZ)), stack.getHoverName(),
                    stack.copyWithCount(1), cluster.count));
        }
        return aggregates;
    }

    /** 贪心分簇过程中的一个临时簇：累加质心，并记住「最近那一份」用于定位/发光。 */
    private static final class DropCluster {
        private final ItemEntity nearest;
        private int count;
        private double sumX;
        private double sumY;
        private double sumZ;

        private DropCluster(ItemEntity first) {
            this.nearest = first;
            this.count = 1;
            this.sumX = first.getX();
            this.sumY = first.getY();
            this.sumZ = first.getZ();
        }

        private void add(ItemEntity entity) {
            this.count++;
            this.sumX += entity.getX();
            this.sumY += entity.getY();
            this.sumZ += entity.getZ();
        }

        /** 某点到簇质心的距离 —— 判定「算不算同一堆」用的就是它。 */
        private double gapToCenter(double x, double y, double z) {
            double cx = this.sumX / this.count;
            double cy = this.sumY / this.count;
            double cz = this.sumZ / this.count;
            double dx = x - cx;
            double dy = y - cy;
            double dz = z - cz;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
    }

    /**
     * 一个「空间簇」聚合出来的掉落物记录：物品 id + 该簇的总数 + 该簇最近那一份的位置。
     *
     * @param nearestId 该簇里离发起者最近那一份的实体 id —— 点选定位、发光都用它
     * @param sample    示例 stack，数量已归一为 1（图标和物品名从它取）
     * @param count     该簇内掉落物份数（一份掉落物记 1）
     */
    private record ItemAggregate(
            ResourceLocation itemId,
            int nearestId,
            double x,
            double y,
            double z,
            double distance,
            Component name,
            ItemStack sample,
            int count) {
    }
}
