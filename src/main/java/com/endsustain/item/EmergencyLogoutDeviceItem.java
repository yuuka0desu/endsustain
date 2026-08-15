package com.endsustain.item;

import com.endsustain.entity.boss.FinaleEndsustainEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.util.List;

public class EmergencyLogoutDeviceItem extends Item {
    private static final double CLEAR_RADIUS = 128.0D;

    public EmergencyLogoutDeviceItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            return InteractionResultHolder.sidedSuccess(stack, true);
        }
        if (!(level instanceof ServerLevel server)) {
            return InteractionResultHolder.pass(stack);
        }

        AABB area = player.getBoundingBox().inflate(CLEAR_RADIUS);
        List<FinaleEndsustainEntity> bosses = server.getEntitiesOfClass(FinaleEndsustainEntity.class, area, FinaleEndsustainEntity::isAlive);
        for (FinaleEndsustainEntity boss : bosses) {
            boss.discard();
        }

        player.getCooldowns().addCooldown(this, 20);
        player.sendSystemMessage(Component.literal("起爆器已令附近的蘸酱无心战斗：" + bosses.size() + " 个"));
        return InteractionResultHolder.sidedSuccess(stack, false);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("使用后会令蘸酱无心战斗(?)"));
    }
}
