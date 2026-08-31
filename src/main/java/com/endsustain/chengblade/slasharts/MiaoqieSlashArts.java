package com.endsustain.chengblade.slasharts;

import com.endsustain.chengblade.ChengGameplayEvents;
import com.endsustain.chengblade.registry.ChengEffectRegistry;
import com.endsustain.chengblade.specialeffects.BeingStirFriedEffect;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.capability.concentrationrank.IConcentrationRank;
import mods.flammpfeil.slashblade.capability.inputstate.CapabilityInputState;
import mods.flammpfeil.slashblade.capability.inputstate.IInputState;
import mods.flammpfeil.slashblade.entity.EntitySlashEffect;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.InputCommand;
import mods.flammpfeil.slashblade.util.KnockBacks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.Comparator;
import java.util.List;

public final class MiaoqieSlashArts {
    public static final int PROUD_SOUL_COST = 520;
    /** 突刺最大距离（格）。 */
    private static final double MAX_DASH_DISTANCE = 10.0D;
    /** 突刺最低距离（格），蓄力不足 1 秒时按此距离。 */
    private static final double MIN_DASH_DISTANCE = 1.25D;
    /** 蓄力下限：1 秒（20 tick）。 */
    private static final long CHARGE_MIN_TICKS = 20L;
    /** 蓄力上限：3 秒（60 tick），此后距离封顶 10 格。 */
    private static final long CHARGE_MAX_TICKS = 60L;
    /** 最多可穿过的墙体厚度（格），1 格墙 + 采样步长余量。 */
    private static final double WALL_THICKNESS_LIMIT = 1.25D;
    /** 路径采样步长（格）。 */
    private static final double SCAN_STEP = 0.25D;
    private static final double DAMAGE_MULTIPLIER = 7159.0D;
    private static final int ORANGE = 0xF28C28;

    private MiaoqieSlashArts() {
    }

    public static void doMiaoqie(LivingEntity user) {
        Level level = user.level();
        if (level.isClientSide()) {
            return;
        }

        user.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
            if (state.getProudSoulCount() < PROUD_SOUL_COST) {
                return;
            }
            state.setProudSoulCount(state.getProudSoulCount() - PROUD_SOUL_COST);
            state.sendChanges(user);

            Vec3 start = user.position().add(0.0D, user.getBbHeight() * 0.5D, 0.0D);
            Vec3 horizontalDir = new Vec3(user.getLookAngle().x, 0.0D, user.getLookAngle().z).normalize();
            // 突刺距离：0-10 格，随蓄力时间线性增长
            Vec3 end = start.add(horizontalDir.scale(dashDistanceForCharge(user)));
            end = clampHorizontalDestination(user, end);

            double damage = state.getBaseAttackModifier() * DAMAGE_MULTIPLIER;
            damagePath(level, user, start, end, damage);

            ServerLevel serverLevel = (ServerLevel) level;
            spawnOrangeEnderParticles(serverLevel, start);
            spawnDescendingBeam(serverLevel, user, start, end, horizontalDir);
            user.getPersistentData().putLong(ChengGameplayEvents.CHENG_IMMUNE_KEY, level.getGameTime() + 10L);
            teleportUser(user, end);
            spawnOrangeEnderParticles(serverLevel, end);
            grantDashAfterEffects(user);
        });
    }

    public static void doHungryAmbush(LivingEntity user) {
        Level level = user.level();
        if (level.isClientSide()) {
            return;
        }
        user.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
            Vec3 origin = user.position().add(0.0D, user.getBbHeight() * 0.55D, 0.0D);
            Vec3 forward = user.getLookAngle().normalize();
            user.setDeltaMovement(forward.scale(1.15D).add(0.0D, 0.08D, 0.0D));
            user.hurtMarked = true;
            LivingEntity target = level.getEntitiesOfClass(LivingEntity.class, user.getBoundingBox().inflate(3.0D),
                    entity -> entity != user && entity.isAlive())
                    .stream().min(Comparator.comparingDouble(user::distanceToSqr)).orElse(null);
            if (target != null) {
                target.addEffect(new MobEffectInstance(ChengEffectRegistry.BEING_STIR_FRIED.get(),
                        BeingStirFriedEffect.DURATION, 0, false, true, true));
                target.hurt(level.damageSources().mobAttack(user), Math.max(1.0F, state.getBaseAttackModifier() * 0.35F));
            }
        });
    }

    /**
     * 蓄力时间 → 突刺距离：
     * 通过 SlashBlade 输入状态监听右键最后按下时间，计算真实按住时长。
     * 1 秒（20 tick）以下为最低 1.25 格；1-3 秒线性增长；3 秒（60 tick）封顶 10 格。
     */
    private static double dashDistanceForCharge(LivingEntity user) {
        long now = user.level().getGameTime();
        long pressTime = now;
        try {
            IInputState input = user.getCapability(CapabilityInputState.INPUT_STATE).orElse(null);
            if (input != null) {
                long last = input.getLastPressTime(InputCommand.R_CLICK);
                if (last > 0L && last <= now) {
                    pressTime = last;
                }
            }
        } catch (Throwable ignored) {
        }
        long charge = Math.max(0L, now - pressTime);
        if (charge <= CHARGE_MIN_TICKS) {
            return MIN_DASH_DISTANCE;
        }
        double progress = Math.min(1.0D,
                (double) (charge - CHARGE_MIN_TICKS) / (double) (CHARGE_MAX_TICKS - CHARGE_MIN_TICKS));
        return MIN_DASH_DISTANCE + (MAX_DASH_DISTANCE - MIN_DASH_DISTANCE) * progress;
    }

    /**
     * 水平路径钳制（仅水平位移）：
     * - 最多穿过厚度 1 格的墙壁，超过则回退到穿墙前的位置；
     * - 落点脚下必须有可站立方块支撑，否则退回路径上的上一个可落脚点。
     */
    private static Vec3 clampHorizontalDestination(LivingEntity user, Vec3 target) {
        Level level = user.level();
        Vec3 origin = user.position();
        Vec3 delta = target.subtract(origin);
        double total = delta.length();
        if (total <= 0.01D) {
            return target;
        }
        Vec3 dir = delta.scale(1.0D / total);
        double solidThickness = 0.0D;
        double lastClear = 0.0D;
        double lastValid = 0.0D; // 起点默认可落脚（玩家当前正站立于此）
        double step = SCAN_STEP;
        for (double d = step; d <= total + 1.0E-3D; d += step) {
            Vec3 point = origin.add(dir.scale(d));
            if (isBodySolid(level, user, point)) {
                solidThickness += step;
                if (solidThickness > WALL_THICKNESS_LIMIT) {
                    // 墙厚超过 1 格：回退到穿墙前最后一个可落脚点
                    return origin.add(dir.scale(Math.max(lastClear, lastValid)));
                }
            } else {
                solidThickness = 0.0D;
                lastClear = d;
                if (hasGroundSupport(level, user, point)) {
                    lastValid = d;
                }
            }
        }
        return origin.add(dir.scale(lastValid));
    }

    /** 检测落点脚下是否存在可站立的实心方块。 */
    private static boolean hasGroundSupport(Level level, LivingEntity user, Vec3 point) {
        BlockPos below = BlockPos.containing(point.x, point.y - user.getBbHeight() * 0.5D - 0.5D, point.z);
        return level.getBlockState(below).isSolid();
    }

    private static boolean isBodySolid(Level level, LivingEntity user, Vec3 point) {
        int checks = user.getBbHeight() > 1.0F ? 3 : 2;
        for (int i = 0; i < checks; i++) {
            double y = point.y + 0.2D + (user.getBbHeight() - 0.4D) * i / (checks - 1);
            BlockPos pos = BlockPos.containing(point.x, y, point.z);
            if (level.getBlockState(pos).isCollisionShapeFullBlock(level, pos)) {
                return true;
            }
        }
        return false;
    }

    private static void damagePath(Level level, LivingEntity user, Vec3 start, Vec3 end, double damage) {
        Vec3 min = new Vec3(Math.min(start.x, end.x), Math.min(start.y, end.y), Math.min(start.z, end.z));
        Vec3 max = new Vec3(Math.max(start.x, end.x), Math.max(start.y, end.y), Math.max(start.z, end.z));
        AABB corridor = new AABB(min, max).inflate(1.5D, 1.0D, 1.5D);
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class, corridor,
                entity -> entity != user && entity.isAlive());
        for (LivingEntity target : targets) {
            target.addEffect(new MobEffectInstance(ChengEffectRegistry.BEING_STIR_FRIED.get(),
                    BeingStirFriedEffect.DURATION, 0, false, true, true));
            if (user instanceof ServerPlayer player) {
                target.hurt(level.damageSources().playerAttack(player), (float) damage);
            } else {
                target.hurt(level.damageSources().mobAttack(user), (float) damage);
            }
            ChengGameplayEvents.applyFirstDelicious(target);
        }
    }

    private static void spawnDescendingBeam(ServerLevel level, LivingEntity user, Vec3 start, Vec3 end,
                                            Vec3 horizontalDir) {
        EntitySlashEffect beam = new EntitySlashEffect(SlashBlade.RegistryEvents.SlashEffect, level);
        Vec3 mid = start.add(end).scale(0.5D);
        beam.setPos(mid.x, start.y, mid.z);
        float yaw = (float) Mth.wrapDegrees(Math.toDegrees(Math.atan2(-horizontalDir.x, horizontalDir.z)));
        beam.setYRot(yaw);
        beam.setXRot(0.0F);
        beam.setRotationRoll(0.0F);
        beam.setBaseSize(2.0F);
        beam.setColor(ORANGE);
        beam.setNoClip(true);
        beam.setIsCritical(true);
        beam.setLifetime(12);
        beam.setShooter(user);
        beam.setRank(IConcentrationRank.ConcentrationRanks.S.level);
        beam.setDamage(0.0D);
        beam.setKnockBack(KnockBacks.cancel);
        beam.setDeltaMovement(0.0D, -0.7D, 0.0D);
        level.addFreshEntity(beam);
    }

    private static void spawnOrangeEnderParticles(ServerLevel level, Vec3 center) {
        Vector3f orange = new Vector3f(1.0F, 0.5F, 0.1F);
        for (int i = 0; i < 60; i++) {
            double ox = (level.random.nextDouble() - 0.5D) * 1.2D;
            double oy = level.random.nextDouble() * 1.8D;
            double oz = (level.random.nextDouble() - 0.5D) * 1.2D;
            level.sendParticles(new DustParticleOptions(orange, 1.0F),
                    center.x + ox, center.y + oy, center.z + oz,
                    1, 0.0D, 0.08D, 0.0D, 0.02D);
        }
    }

    private static void teleportUser(LivingEntity user, Vec3 destination) {
        if (user instanceof ServerPlayer player) {
            player.teleportTo(destination.x, user.getY(), destination.z);
        } else {
            user.moveTo(destination.x, user.getY(), destination.z, user.getYRot(), user.getXRot());
        }
    }

    private static void grantDashAfterEffects(LivingEntity user) {
        user.addEffect(new MobEffectInstance(MobEffects.DAMAGE_RESISTANCE, 20, 3, false, false, true));
        user.addEffect(new MobEffectInstance(MobEffects.DAMAGE_BOOST, 60, 3, false, false, true));
    }
}
