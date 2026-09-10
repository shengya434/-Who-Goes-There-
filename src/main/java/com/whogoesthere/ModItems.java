package com.whogoesthere;

import com.whogoesthere.item.EraserItem;
import com.whogoesthere.item.StampItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * 本模组的物品登记处。
 *
 * <p>目前两件：{@link #STAMP 印章}、{@link #ERASER 橡皮擦}。都只堆叠一个 —— 它们是工具，
 * 不是消耗品（盖章不会消耗印章）。</p>
 *
 * <p>创造模式里挂在原版「工具与实用物品」标签页下（见 {@link #addCreativeTabItems}），
 * 不新建标签页，少一个界面问题少一份折腾。</p>
 */
public final class ModItems {

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(WhoGoesThere.MOD_ID);

    /** 印章：右键生物 → 永加载 + 不消失 + 实体栏置顶。 */
    public static final DeferredItem<Item> STAMP =
            ITEMS.registerItem("stamp", StampItem::new, new Item.Properties().stacksTo(1));

    /** 橡皮擦：右键生物 → 移除上面全部效果。 */
    public static final DeferredItem<Item> ERASER =
            ITEMS.registerItem("eraser", EraserItem::new, new Item.Properties().stacksTo(1));

    private ModItems() {
    }

    /** 在 mod 总线上监听 {@link BuildCreativeModeTabContentsEvent}。 */
    public static void addCreativeTabItems(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(STAMP);
            event.accept(ERASER);
        }
    }
}
