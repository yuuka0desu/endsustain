package com.endsustain.item;

import com.endsustain.entity.projectile.NullVoidProjectileEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
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
 * 使用前需要游戏内口令解锁（/endsustain unlock &lt;密钥&gt;）。
 */
public class NullVoidBladeItem extends TridentItem {
    private static final String UNLOCK_KEY = "yuuka0desu";
    private static final String UNLOCK_FLAG = "EndsustainVoidBreakerUnlocked";
    private static final String AUTHORIZED_TAG = "void_authorized";

    public NullVoidBladeItem(Properties properties) {
        super(properties);
    }

    public static boolean isUnlocked(Player player) {
        return player.getPersistentData().getBoolean(UNLOCK_FLAG);
    }

    /** 口令匹配时解锁并直接向玩家背包发放一把带授权标记的武器。 */
    public static boolean tryUnlock(Player player, String key) {
        if (!UNLOCK_KEY.equals(key)) return false;
        player.getPersistentData().putBoolean(UNLOCK_FLAG, true);
        ItemStack gift = new ItemStack(com.endsustain.item.ModItems.NULL_VOID_BLADE.get());
        gift.getOrCreateTag().putBoolean(AUTHORIZED_TAG, true);
        if (!player.getInventory().add(gift)) {
            player.drop(gift, false);
        }
        return true;
    }

    @Override
    public void inventoryTick(ItemStack stack, Level level, Entity entity, int slot, boolean selected) {
        super.inventoryTick(stack, level, entity, slot, selected);
        // 无授权标记的物品（give / 创造栏 / 其他途径）一律从背包清除。
        if (level.isClientSide || !(entity instanceof Player player)) return;
        if (stack.getOrCreateTag().getBoolean(AUTHORIZED_TAG)) return;
        player.getInventory().setItem(slot, ItemStack.EMPTY);
    }

    @Override
    public void releaseUsing(ItemStack stack, Level level, LivingEntity living, int timeLeft) {
        if (!(living instanceof Player player)) return;
        if (!isUnlocked(player)) {
            if (!level.isClientSide) {
                player.displayClientMessage(Component.literal("虚空断刃尚未解锁，使用 /endsustain unlock <密钥> 解锁。"), true);
            }
            return;
        }
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
        tooltip.add(Component.literal("需要口令解锁后才能投掷"));
    }
}
