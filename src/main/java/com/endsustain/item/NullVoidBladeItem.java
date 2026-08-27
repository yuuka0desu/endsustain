package com.endsustain.item;

import com.endsustain.entity.projectile.NullVoidProjectileEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.TridentItem;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 三叉戟投掷逻辑：蓄力后投掷出无抛物线直线飞行体。
 * 飞行体本身不造成伤害，只对路径上的其他玩家投递双重崩溃信号。
 */
public class NullVoidBladeItem extends TridentItem {
    public NullVoidBladeItem(Properties properties) {
        super(properties);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (!(living instanceof Player player)) return;
        int charge = this.getUseDuration(stack) - timeLeft;
        if (charge < 10) return;
        if (level.isClientSide) return;

        stack.hurtAndBreak(1, player, user -> user.broadcastBreakEvent(living.getUsedItemHand()));
        if (stack.isEmpty()) return;

        NullVoidProjectileEntity projectile = new NullVoidProjectileEntity(level, player);
        level.addFreshEntity(projectile);
        level.playSound(null, player, SoundEvents.TRIDENT_THROW, SoundSource.PLAYERS, 1.0F, 1.0F);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.literal("蓄力投掷 · 直线飞行 · 无抛物线"));
        tooltip.add(Component.literal("路径上的其他玩家将收到崩溃信号"));
    }
}
