package com.whogoesthere.item;

import com.whogoesthere.server.StampManager;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 印章。右键生物给它盖一个章：永加载 + 不掉落消失 + 模组实体栏置顶。
 *
 * <p>效果全部在服务端 {@link StampManager#stamp} 里做；客户端只负责把「用过了」这个
 * 结果返回，免得手臂空挥。</p>
 */
public class StampItem extends Item {

    public StampItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player, LivingEntity target,
                                                  InteractionHand hand) {
        // 只在服务端生效 —— 两端都跑会让提示弹两次
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (player instanceof ServerPlayer serverPlayer) {
            StampManager.stamp(serverPlayer, target);
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.whogoesthere.stamp.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
