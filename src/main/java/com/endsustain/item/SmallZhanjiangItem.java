package com.endsustain.item;

import com.endsustain.effect.ModEffects;
import com.endsustain.event.SmallZhanjiangEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import javax.annotation.Nullable;
import java.util.List;
import top.theillusivec4.curios.api.SlotContext;
import top.theillusivec4.curios.api.type.capability.ICurioItem;

public class SmallZhanjiangItem extends ZhajiangDollItem implements ICurioItem {
    public SmallZhanjiangItem(Properties properties) { super(properties); }

    @Override
    public boolean canEquip(SlotContext slotContext, ItemStack stack) {
        return "guide".equals(slotContext.identifier());
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.summary").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.effects").withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.protection").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.blessing").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.curse").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.slots").withStyle(ChatFormatting.DARK_PURPLE));
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.slot_bonus").withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.endsustain.small_zhanjiang.companion").withStyle(ChatFormatting.GRAY));
    }

    @Override
    public void onEquip(String identifier, int index, LivingEntity livingEntity, ItemStack stack) {
        if (!"guide".equals(identifier) || !(livingEntity instanceof ServerPlayer player) || player.level().isClientSide) return;
        player.getPersistentData().putBoolean(SmallZhanjiangEvents.ACTIVE, true);
        player.getPersistentData().putInt(SmallZhanjiangEvents.LAST_ACTIVE_TICK, player.tickCount);
        SmallZhanjiangEvents.ensureCompanion(player, true);
    }

    @Override
    public void onUnequip(String identifier, int index, LivingEntity livingEntity, ItemStack stack) {
        if (!"guide".equals(identifier) || !(livingEntity instanceof ServerPlayer player) || player.level().isClientSide) return;
        player.getPersistentData().putBoolean(SmallZhanjiangEvents.ACTIVE, false);
        SmallZhanjiangEvents.removeCompanion(player);
    }

    @Override
    public void curioTick(SlotContext slotContext, ItemStack stack) {
        if (!"guide".equals(slotContext.identifier())) return;
        LivingEntity wearer = slotContext.entity();
        wearer.addEffect(new MobEffectInstance(ModEffects.FINALE_PROTECTION.get(), 40, 0, false, true, true));
        wearer.addEffect(new MobEffectInstance(ModEffects.FINALE_BLESSING.get(), 40, 0, false, true, true));
        wearer.addEffect(new MobEffectInstance(ModEffects.FINALE_CURSE.get(), 40, 0, false, true, true));
        if (wearer.getHealth() > wearer.getMaxHealth()) wearer.setHealth(wearer.getMaxHealth());
        if (wearer instanceof ServerPlayer player && !player.level().isClientSide) {
            player.getPersistentData().putInt(SmallZhanjiangEvents.LAST_ACTIVE_TICK, player.tickCount);
            SmallZhanjiangEvents.ensureCompanion(player, true);
        }
    }
}
