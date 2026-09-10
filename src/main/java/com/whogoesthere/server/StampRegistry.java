package com.whogoesthere.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * 印章登记表：被盖章实体的「跨维度点名册」。
 *
 * <p>存在主世界（overworld）的 {@code DataStorage} 里，也就是 {@code level.dat} 那一份，
 * 与维度无关 —— 所以一只跑去地狱的猪，在总表里还是一条，记得住它在哪个维度、大致坐标。</p>
 *
 * <p>每条记录只有 UUID + 维度 + 坐标。名字、类型这些实时信息扫描时现查（见
 * {@link ScanService}），不在表里缓存，免得实体死了名字还对不上。</p>
 *
 * <p>用 {@link LinkedHashMap} 是为了保留盖章顺序 —— 置顶分组里按「先盖的排在前面」呈现。</p>
 */
public final class StampRegistry extends SavedData {

    private static final String FILE_ID = "whogoesthere_stamps";
    private static final String KEY_ENTRIES = "Entries";
    private static final String KEY_UUID = "Uuid";
    private static final String KEY_DIMENSION = "Dimension";
    private static final String KEY_X = "X";
    private static final String KEY_Y = "Y";
    private static final String KEY_Z = "Z";

    private final Map<UUID, Entry> entries = new LinkedHashMap<>();

    private StampRegistry() {
    }

    /** 取主世界那份总表；没有就新建。 */
    public static StampRegistry get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(StampRegistry::new, StampRegistry::load), FILE_ID);
    }

    private static StampRegistry load(CompoundTag tag, HolderLookup.Provider registries) {
        StampRegistry data = new StampRegistry();
        ListTag list = tag.getList(KEY_ENTRIES, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entryTag = list.getCompound(i);
            try {
                UUID uuid = UUID.fromString(entryTag.getString(KEY_UUID));
                ResourceLocation dimension = ResourceLocation.parse(entryTag.getString(KEY_DIMENSION));
                data.entries.put(uuid, new Entry(dimension,
                        entryTag.getDouble(KEY_X), entryTag.getDouble(KEY_Y), entryTag.getDouble(KEY_Z)));
            } catch (RuntimeException ignored) {
                // 单条烂数据不该拖垮整张表 —— 跳过它，其余照读
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Entry> e : this.entries.entrySet()) {
            CompoundTag entryTag = new CompoundTag();
            entryTag.putString(KEY_UUID, e.getKey().toString());
            entryTag.putString(KEY_DIMENSION, e.getValue().dimension().toString());
            entryTag.putDouble(KEY_X, e.getValue().x());
            entryTag.putDouble(KEY_Y, e.getValue().y());
            entryTag.putDouble(KEY_Z, e.getValue().z());
            list.add(entryTag);
        }
        tag.put(KEY_ENTRIES, list);
        return tag;
    }

    public boolean contains(UUID uuid) {
        return this.entries.containsKey(uuid);
    }

    public int size() {
        return this.entries.size();
    }

    /** 登记顺序的 UUID 快照（调用方自行 copy 防并发修改）。 */
    public Set<UUID> entryIds() {
        return this.entries.keySet();
    }

    public Entry get(UUID uuid) {
        return this.entries.get(uuid);
    }

    /** 新增一条（盖章）。 */
    public void put(UUID uuid, ResourceLocation dimension, double x, double y, double z) {
        this.entries.put(uuid, new Entry(dimension, x, y, z));
        setDirty();
    }

    /** 更新既有记录（实体移动 / 跨维度）。 */
    public void update(UUID uuid, ResourceLocation dimension, double x, double y, double z) {
        this.entries.put(uuid, new Entry(dimension, x, y, z));
        setDirty();
    }

    /** 移除一条（擦除 / 死亡 / 清理）。 */
    public void remove(UUID uuid) {
        if (this.entries.remove(uuid) != null) {
            setDirty();
        }
    }

    /**
     * 一条登记：实体在哪个维度、大概在哪。
     *
     * @param dimension 维度 id（如 {@code minecraft:the_nether}）
     * @param x         最近一次同步到的坐标
     */
    public record Entry(ResourceLocation dimension, double x, double y, double z) {
    }
}
