package com.whogoesthere.network.payload;

import com.whogoesthere.WhoGoesThere;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/**
 * S2C — 扫描结果：一份排好序的名单（最多 {@value #MAX_ENTRIES} 条）。
 *
 * <p>名单分两段：</p>
 * <ol>
 *   <li><b>置顶段</b> —— 盖了章、永加载的实体（{@link Kind#PINNED}），固定在列表最顶部，
 *       可能来自别的维度，所以带 UUID + 维度；</li>
 *   <li><b>常规段</b> —— 发起者所在维度里按距离升序的活物与聚合掉落物。</li>
 * </ol>
 */
public record ScanResultPayload(List<EntityInfo> entries) implements CustomPacketPayload {

    /** 服务端硬上限，防止一次性把太多东西塞进一个包里。v0.2 由 200 提到 500。 */
    public static final int MAX_ENTRIES = 500;

    public static final CustomPacketPayload.Type<ScanResultPayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(WhoGoesThere.MOD_ID, "scan_result"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload> STREAM_CODEC =
            new StreamCodec<RegistryFriendlyByteBuf, ScanResultPayload>() {
                @Override
                public ScanResultPayload decode(RegistryFriendlyByteBuf buf) {
                    int size = buf.readVarInt();
                    List<EntityInfo> list = new ArrayList<>(Math.max(0, Math.min(size, MAX_ENTRIES)));
                    for (int i = 0; i < size; i++) {
                        list.add(decodeEntry(buf));
                    }
                    return new ScanResultPayload(list);
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buf, ScanResultPayload payload) {
                    List<EntityInfo> list = payload.entries();
                    buf.writeVarInt(list.size());
                    for (EntityInfo entry : list) {
                        encodeEntry(buf, entry);
                    }
                }
            };

    private static void encodeEntry(RegistryFriendlyByteBuf buf, EntityInfo entry) {
        buf.writeVarInt(entry.kind().ordinal());
        buf.writeUtf(entry.namespace());
        buf.writeVarInt(entry.entityId());
        buf.writeResourceLocation(entry.typeId());
        ComponentSerialization.STREAM_CODEC.encode(buf, entry.name());
        buf.writeDouble(entry.x());
        buf.writeDouble(entry.y());
        buf.writeDouble(entry.z());
        buf.writeDouble(entry.distance());
        buf.writeResourceLocation(entry.dimension());
        buf.writeVarInt(entry.count());
        // 只有掉落物才带示例 stack：ItemStack.STREAM_CODEC 是「严格版」，
        // 给它一个 ItemStack.EMPTY 会直接抛 EncoderException，所以别的条目根本不写这一段。
        if (entry.kind() == Kind.ITEM) {
            ItemStack.STREAM_CODEC.encode(buf, entry.stack());
        }
        // 置顶条目带 UUID —— 点它时用于跨维度定位 / 发光（entityId 出了本维度就失效了）
        if (entry.kind() == Kind.PINNED) {
            UUID uuid = entry.uuid();
            buf.writeBoolean(uuid != null);
            if (uuid != null) {
                buf.writeLong(uuid.getMostSignificantBits());
                buf.writeLong(uuid.getLeastSignificantBits());
            }
        }
    }

    private static EntityInfo decodeEntry(RegistryFriendlyByteBuf buf) {
        int rawKind = buf.readVarInt();
        Kind[] kinds = Kind.values();
        Kind kind = rawKind >= 0 && rawKind < kinds.length ? kinds[rawKind] : Kind.LIVING;

        String namespace = buf.readUtf();
        int entityId = buf.readVarInt();
        ResourceLocation typeId = buf.readResourceLocation();
        Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
        double x = buf.readDouble();
        double y = buf.readDouble();
        double z = buf.readDouble();
        double distance = buf.readDouble();
        ResourceLocation dimension = buf.readResourceLocation();
        int count = buf.readVarInt();
        ItemStack stack = kind == Kind.ITEM ? ItemStack.STREAM_CODEC.decode(buf) : ItemStack.EMPTY;
        UUID uuid = kind == Kind.PINNED && buf.readBoolean()
                ? new UUID(buf.readLong(), buf.readLong())
                : null;

        return new EntityInfo(kind, namespace, entityId, typeId, name, x, y, z, distance, dimension, count, stack,
                kind == Kind.PINNED, uuid);
    }

    /** 活物：一格一条，{@code count} 恒为 1，不带 stack。 */
    public static EntityInfo living(String namespace, int entityId, ResourceLocation typeId, Component name,
                                    double x, double y, double z, double distance, ResourceLocation dimension) {
        return new EntityInfo(Kind.LIVING, namespace, entityId, typeId, name, x, y, z, distance, dimension, 1,
                ItemStack.EMPTY, false, null);
    }

    /**
     * 掉落物：同类物品聚合成一条。
     *
     * @param entityId 该类物品里**最近那一份**的实体 id —— 点选定位、发光都用它
     * @param count    总数（地面上同一物品的总个数）
     * @param stack    示例 stack（数量已归一为 1，只用来取图标和物品名）
     */
    public static EntityInfo item(String namespace, int entityId, ResourceLocation typeId, Component name,
                                  double x, double y, double z, double distance, ResourceLocation dimension,
                                  int count, ItemStack stack) {
        return new EntityInfo(Kind.ITEM, namespace, entityId, typeId, name, x, y, z, distance, dimension, count, stack,
                false, null);
    }

    /**
     * 置顶条目：盖了章的实体。
     *
     * @param distance  与发起者同维度时是真实距离，异维度为 {@code -1}（界面显示「异界」）
     * @param uuid      实体 UUID —— 跨维度定位、发光都靠它
     */
    public static EntityInfo pinned(String namespace, int entityId, ResourceLocation typeId, Component name,
                                    double x, double y, double z, double distance, ResourceLocation dimension,
                                    UUID uuid) {
        return new EntityInfo(Kind.PINNED, namespace, entityId, typeId, name, x, y, z, distance, dimension, 1,
                ItemStack.EMPTY, true, uuid);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 名单里的一条到底是什么。 */
    public enum Kind {
        /** 活物（生物、玩家、盔甲架这类 {@code LivingEntity}）。 */
        LIVING,
        /** 掉落物（{@code ItemEntity}），同类已聚合。 */
        ITEM,
        /** 盖了章的实体 —— 固定置顶。 */
        PINNED
    }

    /**
     * 一条扫描记录。
     *
     * @param kind      条目种类（活物 / 掉落物聚合 / 置顶）
     * @param namespace 注册命名空间（{@code minecraft}、{@code mekanism}…），模组分类靠它
     * @param entityId  实体网络 id（掉落物是「最近那一份」的 id），点选定位时原样回传
     * @param typeId    注册 id（活物是实体类型，掉落物是物品 id）
     * @param name      显示名（掉落物为 {@code ItemStack#getHoverName()}）
     * @param distance  到发起者的距离，已保留 1 位小数；异维度的置顶条目为 {@code -1}
     * @param dimension 所在维度 id
     * @param count     聚合数量；活物与置顶恒为 1，掉落物为总数
     * @param stack     掉落物的示例 stack；其余为 {@link ItemStack#EMPTY}
     * @param pinned    是否置顶（盖了章）
     * @param uuid      置顶条目的实体 UUID；其余为 {@code null}
     */
    public record EntityInfo(
            Kind kind,
            String namespace,
            int entityId,
            ResourceLocation typeId,
            Component name,
            double x,
            double y,
            double z,
            double distance,
            ResourceLocation dimension,
            int count,
            ItemStack stack,
            boolean pinned,
            UUID uuid) {

        /** 是不是掉落物。 */
        public boolean isItem() {
            return this.kind == Kind.ITEM;
        }

        /** 是不是置顶条目（盖了章）。 */
        public boolean isPinned() {
            return this.kind == Kind.PINNED;
        }

        /** 是不是「一堆」——只有聚合后数量大于 1 才显示 ×N。 */
        public boolean isStacked() {
            return this.count > 1;
        }

        /** 与发起者不在同一维度（界面不显示距离，显示「异界」）。 */
        public boolean isCrossDimension() {
            return this.distance < 0.0D;
        }
    }
}
