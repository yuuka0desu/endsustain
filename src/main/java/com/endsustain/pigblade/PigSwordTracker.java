package com.endsustain.pigblade;

import com.endsustain.pigblade.registry.PigBladeRegistry;
import mods.flammpfeil.slashblade.entity.EntitySlashEffect;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 猪咪幻影剑追踪器：
 * 幻影剑使用原模组实体（视觉原版），但命中与行为由本追踪器自行结算——
 * 每 tick 检测剑周围目标，命中后执行伤害 / 猪化 / Boss 仆从 / 负面状态 / 猪音效 / 0.1% 附加伤害。
 */
public final class PigSwordTracker {
    private static final double HIT_RADIUS = 1.4D;

    private static final Map<EntitySlashEffect, SwordSpec> ACTIVE = new ConcurrentHashMap<>();

    private PigSwordTracker() {
    }

    /** 注册一枚待结算的幻影剑：该剑发射的所有幻影剑统一携带全部命中行为。 */
    public static void track(EntitySlashEffect sword, LivingEntity shooter, double damage,
                             boolean pigForce) {
        ACTIVE.put(sword, new SwordSpec(shooter, (float) damage, pigForce));
    }
    /** 每服务端 tick 调用：校验标签并执行碰撞检测与命中结算。 */
    public static void tick() {
        if (ACTIVE.isEmpty()) return;
        ACTIVE.entrySet().removeIf(entry -> entry.getKey().isRemoved());
        for (Map.Entry<EntitySlashEffect, SwordSpec> entry : ACTIVE.entrySet()) {
            EntitySlashEffect sword = entry.getKey();
            SwordSpec spec = entry.getValue();
            // 标签校验：仅拥有猪咪剑标签的幻影剑才触发自定义行为
            if (!sword.getTags().contains(PigBladeLogic.PIG_SWORD_TAG)) {
                ACTIVE.remove(sword);
                continue;
            }
            if (sword.level().isClientSide || !sword.isAlive()) continue;
            if (!(sword.level() instanceof ServerLevel level)) continue;

            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class,
                    sword.getBoundingBox().inflate(HIT_RADIUS),
                    t -> t.isAlive() && t != spec.shooter && !(t instanceof PigFormPig)
                            && !PigMinionLogic.isMinion(t)
                            && !(t instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity)
                            && !(t instanceof com.endsustain.entity.boss.QunUEntity))) {
                if (!spec.alreadyHit.add(target.getUUID())) continue;
                com.endsustain.EndSustain.LOGGER.info("[猪咪幻影剑] 命中：{}，剑={}",
                        target.getName().getString(), sword.getId());
                spec.onHit(level, sword, target);
                sword.discard();
                break;
            }
        }
        ACTIVE.entrySet().removeIf(entry -> entry.getKey().isRemoved());
    }

    private static final class SwordSpec {
        private static final float DEBUFF_CHANCE = 0.5F;

        private final LivingEntity shooter;
        private final float damage;
        private final boolean pigForce;
        private final Set<UUID> alreadyHit = new HashSet<>();

        private SwordSpec(LivingEntity shooter, float damage, boolean pigForce) {
            this.shooter = shooter;
            this.damage = damage;
            this.pigForce = pigForce;
        }

        private void onHit(ServerLevel level, EntitySlashEffect sword, LivingEntity target) {
            // ① 命中：先结算剑身伤害
            DamageSource source = shooter instanceof ServerPlayer player
                    ? level.damageSources().playerAttack(player)
                    : level.damageSources().mobAttack(shooter);
            target.invulnerableTime = 0;
            target.hurt(source, Math.max(1.0F, damage));
            if (!target.isAlive()) return;

            // ② 命中后结算（该剑发射的所有幻影剑统一携带）：
            // 猪化：Boss → 召唤仆从 / 普通生物 → 猪化（后续行为作用于猪形态）
            LivingEntity victim = target;
            if (PigFormLogic.isBossLike(target)) {
                PigMinionLogic.summon(target, shooter);
            } else if (PigFormLogic.isValidTarget(target)) {
                PigFormPig pig = PigFormLogic.transform(target, shooter, Math.max(1.0F, damage));
                if (pig != null) {
                    victim = pig;
                }
            }

            // 随机负面状态
            if (victim.isAlive() && level.random.nextFloat() < DEBUFF_CHANCE) {
                applyRandomDebuff(victim);
            }

            // 猪咪之力：猪受伤音效 + 0.1% 最大生命值附加伤害
            if (pigForce && victim.isAlive()) {
                level.playSound(null, victim.blockPosition(), SoundEvents.PIG_HURT,
                        SoundSource.HOSTILE, 1.0F, 1.0F);
                float bonus = victim.getMaxHealth() * 0.001F;
                if (bonus > 0.0F) {
                    victim.invulnerableTime = 0;
                    victim.hurt(shooter instanceof ServerPlayer player
                            ? level.damageSources().playerAttack(player)
                            : level.damageSources().mobAttack(shooter), bonus);
                }
            }
        }

        private static void applyRandomDebuff(LivingEntity target) {
            switch (target.level().random.nextInt(5)) {
                case 0 -> target.addEffect(new MobEffectInstance(MobEffects.WITHER, 100, 0));
                case 1 -> target.addEffect(new MobEffectInstance(MobEffects.POISON, 100, 0));
                case 2 -> target.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 80, 0));
                case 3 -> target.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, 120, 0));
                default -> target.addEffect(new MobEffectInstance(MobEffects.HUNGER, 120, 0));
            }
        }
    }
}
