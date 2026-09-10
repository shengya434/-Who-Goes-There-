package com.whogoesthere.server;

import com.whogoesthere.network.payload.ScanResultPayload;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 扫描本体：把发起者所在维度里所有已加载的 {@link LivingEntity} 找出来。
 *
 * <p>用 {@link ServerLevel#getAllEntities()}，所以不限于玩家周围那圈实体跟踪范围，
 * 被区块加载器保活的远处实体也会被算进来。</p>
 */
public final class ScanService {

    private ScanService() {
    }

    public static List<ScanResultPayload.EntityInfo> scan(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        ResourceLocation dimension = level.dimension().location();

        double originX = player.getX();
        double originY = player.getY();
        double originZ = player.getZ();
        int selfId = player.getId();

        List<ScanResultPayload.EntityInfo> found = new ArrayList<>();

        for (Entity entity : level.getAllEntities()) {
            if (entity.getId() == selfId || entity == player) {
                continue; // 别把自己也报出来
            }
            if (!(entity instanceof LivingEntity)) {
                continue;
            }

            double dx = entity.getX() - originX;
            double dy = entity.getY() - originY;
            double dz = entity.getZ() - originZ;
            double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

            found.add(new ScanResultPayload.EntityInfo(
                    entity.getId(),
                    BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()),
                    entity.getDisplayName(),
                    entity.getX(),
                    entity.getY(),
                    entity.getZ(),
                    round1(distance),
                    dimension));
        }

        found.sort(Comparator.comparingDouble(ScanResultPayload.EntityInfo::distance));
        if (found.size() > ScanResultPayload.MAX_ENTRIES) {
            return new ArrayList<>(found.subList(0, ScanResultPayload.MAX_ENTRIES));
        }
        return found;
    }

    /** 保留 1 位小数。 */
    private static double round1(double value) {
        return Math.round(value * 10.0D) / 10.0D;
    }
}
