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
 * 掉落物按物品类型聚合）。</p>
 */
public final class ScanService {

    /** 找不到实体时，置顶条目用这个占位注册名 —— 免得界面拿到 null。 */
    private static final ResourceLocation UNKNOWN_TYPE =
            ResourceLocation.fromNamespaceAndPath("whogoesthere", "stamped");

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
        // 物品 id -> 该类掉落物的聚合结果（最近一份 + 总数）
        Map<ResourceLocation, ItemAggregate> itemGroups = new HashMap<>();

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
                ItemAggregate previous = itemGroups.get(itemId);
                if (previous == null) {
                    itemGroups.put(itemId, ItemAggregate.first(itemId, itemEntity, distance));
                } else {
                    itemGroups.put(itemId, previous.plus(itemEntity, distance));
                }
            }
        }

        for (ItemAggregate group : itemGroups.values()) {
            found.add(ScanResultPayload.item(
                    group.itemId().getNamespace(),
                    group.nearestId(),
                    group.itemId(),
                    group.name(),
                    group.x(),
                    group.y(),
                    group.z(),
                    group.distance(),
                    dimension,
                    group.count(),
                    group.sample()));
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

    /**
     * 同类掉落物的聚合体：记住「最近那一份」的位置/实体 id/示例 stack，同时累加总数。
     *
     * @param sample 示例 stack，数量已归一为 1（图标和物品名从它取）
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

        static ItemAggregate first(ResourceLocation itemId, ItemEntity entity, double distance) {
            return new ItemAggregate(itemId, entity.getId(), entity.getX(), entity.getY(), entity.getZ(), distance,
                    entity.getItem().getHoverName(), entity.getItem().copyWithCount(1), 1);
        }

        /** 并入另一份：总数 +1；如果新的更近，就把「最近那一份」换成它。 */
        ItemAggregate plus(ItemEntity entity, double otherDistance) {
            if (otherDistance < this.distance) {
                return new ItemAggregate(this.itemId, entity.getId(), entity.getX(), entity.getY(), entity.getZ(),
                        otherDistance, entity.getItem().getHoverName(), entity.getItem().copyWithCount(1),
                        this.count + 1);
            }
            return new ItemAggregate(this.itemId, this.nearestId, this.x, this.y, this.z, this.distance, this.name,
                    this.sample, this.count + 1);
        }
    }
}
