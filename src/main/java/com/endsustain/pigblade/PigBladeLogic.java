package com.endsustain.pigblade;

import com.endsustain.pigblade.registry.PigBladeRegistry;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.entity.EntitySlashEffect;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import mods.flammpfeil.slashblade.slasharts.SakuraEnd;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * 幻影剑发射与目标查找工具。
 * 幻影剑完全使用原模组 EntitySlashEffect（slashblade 原版实体），
 * 仅通过 persistentData 标记命中后行为，命中结算挂接在 LivingHurtEvent。
 */
public final class PigBladeLogic {
    /** 该拔刀剑发射的幻影剑标签：拥有此标签的幻影剑才触发自定义命中行为。 */
    public static final String PIG_SWORD_TAG = "endsustain_pig_sword";

    private PigBladeLogic() {
    }

    /** 判断手持拔刀剑是否携带有效「猪咪之力」。 */
    public static boolean hasPigForce(Player player) {
        ISlashBladeState state = player.getMainHandItem()
                .getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null || !state.hasSpecialEffect(PigBladeRegistry.PIG_FORCE.getId())) return false;
        return SpecialEffect.isEffective(PigBladeRegistry.PIG_FORCE.get(), player.experienceLevel);
    }

    /**
     * 发射一道幻影剑：使用原版挥刀剑气生成函数 SakuraEnd.doSlash（外观与原模组完全一致），
     * 原版区域伤害置 0，命中与猪化 / Boss 召唤 / 随机负面 / 猪音效等行为由 PigSwordTracker 统一结算。
     */
    public static EntitySlashEffect launch(Player player, Vec3 start, Vec3 direction, double damage) {
        ServerLevel level = (ServerLevel) player.level();
        EntitySlashEffect sword = SakuraEnd.doSlash(player, 0.0F, Vec3.ZERO, false, false, 0.0D);
        if (sword == null) {
            com.endsustain.EndSustain.LOGGER.warn("[猪咪幻影剑] 原版剑气生成失败");
            return null;
        }
        sword.setPos(start.x, start.y, start.z);
        sword.setDeltaMovement(direction.normalize().scale(1.4D));
        sword.setLifetime(30);
        sword.setDamage(0.0D);
        // 标签标记：拥有此标签的幻影剑触发猪化 / 召唤 / 随机负面等自定义行为
        sword.addTag(PIG_SWORD_TAG);
        PigSwordTracker.track(sword, player, damage, hasPigForce(player));
        com.endsustain.EndSustain.LOGGER.info("[猪咪幻影剑] 发射：起点={}，伤害={}，实体={}，标签={}",
                start, damage, sword.getId(), sword.getTags());
        return sword;
    }

    /** 从玩家眼前发射一道幻影剑（默认起点）。 */
    public static EntitySlashEffect launch(Player player, Vec3 direction, double damage) {
        return launch(player, player.getEyePosition().add(direction.scale(0.6D)), direction, damage);
    }

    /** 视线方向绕 Y 轴旋转指定度数。 */
    public static Vec3 yawRotate(Vec3 dir, double degrees) {
        double rad = Math.toRadians(degrees);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        return new Vec3(dir.x * cos + dir.z * sin, dir.y, -dir.x * sin + dir.z * cos);
    }

    /** 查找视线方向 maxDist 格内夹角最小的存活目标。 */
    public static LivingEntity findAimTarget(LivingEntity user, double maxDist) {
        Vec3 origin = user.getEyePosition();
        Vec3 dir = user.getLookAngle();
        LivingEntity best = null;
        double bestScore = Double.MAX_VALUE;
        for (LivingEntity entity : user.level().getEntitiesOfClass(LivingEntity.class,
                user.getBoundingBox().inflate(maxDist),
                e -> e != user && e.isAlive() && e != user.getControllingPassenger())) {
            Vec3 center = entity.getBoundingBox().getCenter();
            Vec3 to = center.subtract(origin);
            double distSqr = to.lengthSqr();
            if (distSqr > maxDist * maxDist) continue;
            double dot = to.normalize().dot(dir);
            if (dot < 0.35D) continue;
            double score = distSqr - dot * dot * 100.0D;
            if (score < bestScore) {
                bestScore = score;
                best = entity;
            }
        }
        return best;
    }
}
