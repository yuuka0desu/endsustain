package com.endsustain.item;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

public class IlusiItem extends Item {
    public IlusiItem(Properties properties) {
        super(properties);
    }

    @Override
    public ItemStack finishUsingItem(ItemStack stack, Level level, LivingEntity livingEntity) {
        ItemStack result = super.finishUsingItem(stack, level, livingEntity);
        livingEntity.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20 * 20, 2));
        livingEntity.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 20, 4));
        livingEntity.addEffect(new MobEffectInstance(MobEffects.ABSORPTION, 10 * 20, 19));

        if (!level.isClientSide && livingEntity instanceof ServerPlayer player) {
            ItemStack ate = new ItemStack(ModItems.ILUSI_ATE.get());
            if (!player.getInventory().add(ate)) {
                player.drop(ate, false);
            }
        }
        return result;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("好饿，好饿，好饿"));
    }
}
