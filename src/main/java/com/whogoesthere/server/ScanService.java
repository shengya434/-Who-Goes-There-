package com.whogoesthere.server;

import com.whogoesthere.network.payload.ScanResultPayload;
import com.whogoesthere.network.payload.ScanResultPayload.EntityInfo;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 扫描本体：把发起者所在维度里所有已加载的实体找出来。
 *
 * <p>用 {@link ServerLevel#getAllEntities()}，所以不限于玩家周围那圈实体跟踪范围，
 * 被区块加载器保活的远处实体也会被算进来。</p>
 *
 * <p>v0.2 起收集两类东西：</p>
 * <ul>
 *   <li>{@link LivingEntity} —— 一格一条，和 v0.1 一样；</li>
 *   <li>{@link ItemEntity} —— 按**物品类型**聚合成一条：总数 + 最近那一份的坐标/距离。</li>
 * </ul>
 */
public final class ScanService {

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

        for (Entity entity : level.getAllEntities()) {
            if (entity.getId() == selfId || entity == player) {
                continue; // 别把自己也报出来
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
        if (found.size() > ScanResultPayload.MAX_ENTRIES) {
            return new ArrayList<>(found.subList(0, ScanResultPayload.MAX_ENTRIES));
        }
        return found;
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
