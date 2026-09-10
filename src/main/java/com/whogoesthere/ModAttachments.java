package com.whogoesthere;

import com.mojang.serialization.Codec;
import java.util.function.Supplier;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * 实体数据附件（Data Attachment）。
 *
 * <p>目前只用一个布尔标记 {@link #STAMPED}：这只生物身上有没有「印章」。{@code serialize = true}
 * 表示它会跟着实体写进 NBT，所以存档重载后依然认得出来。</p>
 *
 * <p>注意：<b>没有</b> {@code copyOnDeath()} —— 死亡就该把印章带走，复活/重生不该继承。</p>
 *
 * <p>它是「权威登记表」{@link com.whogoesthere.server.StampRegistry} 的伙伴：登记表负责
 * 跨维度点名与置顶，附件负责「这只实体自己知道被盖过章」。两者任何一个丢了，另一个还在。</p>
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, WhoGoesThere.MOD_ID);

    /** 默认 false；盖章置 true，擦除置 false。 */
    public static final Supplier<AttachmentType<Boolean>> STAMPED =
            ATTACHMENT_TYPES.register("stamped", () -> AttachmentType.builder(() -> Boolean.FALSE)
                    .serialize(Codec.BOOL)
                    .build());

    private ModAttachments() {
    }
}
