package com.endsustain.pigblade.slasharts;

import com.endsustain.pigblade.PigBladeLogic;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 万猪穿心：10 发幻影剑扇形齐射，朝目标方向。
 * 该剑发射的所有幻影剑命中统一触发猪化 / Boss 召唤 / 随机负面状态（PigSwordTracker 结算）。
 */
public final class PigPierceHeart {
    public static final int PROUD_SOUL_COST = 300;
    private static final int SWORD_COUNT = 10;
    private static final double AIM_RANGE = 12.0D;

    private PigPierceHeart() {
    }

    public static void release(LivingEntity user) {
        if (user.level().isClientSide() || !(user instanceof Player player)) return;
        // 防重复：combo 20 tick 内只发射一次（tickAction 多次调用保护，跨次 SA 可再次发射）
        long now = user.level().getGameTime();
        if (now - user.getPersistentData().getLong("EndsustainPigPierceLastCast") < 20L) return;
        user.getPersistentData().putLong("EndsustainPigPierceLastCast", now);

        ISlashBladeState state = player.getMainHandItem()
                .getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null) {
            com.endsustain.EndSustain.LOGGER.warn("[万猪穿心] 无法读取刀身状态，未发射");
            return;
        }

        float damage = Math.max(2.0F, state.getBaseAttackModifier() * 2.0F);
        LivingEntity target = PigBladeLogic.findAimTarget(player, AIM_RANGE);
        Vec3 baseDir = target != null
                ? target.getBoundingBox().getCenter().subtract(player.getEyePosition()).normalize()
                : player.getLookAngle();
        com.endsustain.EndSustain.LOGGER.debug("[万猪穿心] 释放：伤害={}，目标={}，10 发齐射", damage, target);

        // 10 发幻影剑扇形齐射，朝目标方向
        for (int i = 0; i < SWORD_COUNT; i++) {
            double spread = (i - (SWORD_COUNT - 1) / 2.0D) * 4.0D;
            Vec3 dir = PigBladeLogic.yawRotate(baseDir, spread);
            PigBladeLogic.launch(player, dir, damage);
        }
    }
}
