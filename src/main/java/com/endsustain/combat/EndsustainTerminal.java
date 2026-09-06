package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

/**
 * 终焉结算 API（本模组专用）。
 *
 * 不拼接掉落，而是伪造完整正常死亡流程：
 * 从原版 hurt 入口进入，攻击/护甲/死亡事件链自然触发，
 * 生命写入由本模组 mixin 强制归零，绕开血量防御后
 * 由原版死亡分支调用 die()——boss 覆写的自定义死亡动画与掉落全部正常执行。
 */
public final class EndsustainTerminal {
    public static final String TERMINAL_KEY = "EndsustainTerminalState";

    private EndsustainTerminal() {}

    /** 给目标打上终焉标记：此后任何回血/正数生命写入都会被本模组 mixin 拦截。 */
    public static void mark(LivingEntity target) {
        target.getPersistentData().putBoolean(TERMINAL_KEY, true);
    }

    public static boolean isMarked(LivingEntity target) {
        return target.getPersistentData().getBoolean(TERMINAL_KEY);
    }

    public static void unmark(LivingEntity target) {
        target.getPersistentData().remove(TERMINAL_KEY);
    }

    /**
     * 终焉结算（多路径绝对击杀，纯通用 API，无任何针对性调用/反射/读取）：
     * L0 数据项归零 + 上限锁定（绕开 hurt/setHealth 管道，回血/回滚被钳制失效）；
     * L1 管道 hurt 触发完整受伤/死亡事件链；
     * L2 GENERIC_KILL 内置退场兜底（先于目标冷却/免疫检查的分支）；
     * L3 确认死亡后调用 die 启动死亡演出与掉落钩子；
     * L4 未移除时挂入看门狗队列（磨血 + 重试 + 超时移除）。
     */
    public static void terminate(LivingEntity target, DamageSource source, LivingEntity attacker) {
        mark(target);
        clearDefenses(target);

        // 蘸酱 Boss 有独立的死亡尾流程（星辰矢反杀 + finalize 掉落），
        // 不可进入强制移除队列，否则死亡尾会被提前掐断、掉落丢失。
        if (target instanceof com.endsustain.entity.boss.FinaleEndsustainEntity boss) {
            boss.beginCombatDeathTail(source);
            return;
        }

        // L0：生命归零 + 上限锁定
        EntityHealthStateBridge.lockHealthAndCapToZero(target);
        if (target.isRemoved()) return;

        // L1：管道 hurt（完整受伤链；生命写入由 mixin 强制归零，原版死亡分支触发 die）
        try {
            target.hurt(source, Float.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        if (target.isRemoved()) return;

        // L2：GENERIC_KILL 内置退场兜底（无攻击者、伤害超过 5 倍最大生命）
        if (!TrueKillUtil.isDeathTransactionStarted(target)) {
            try {
                target.hurt(target.damageSources().genericKill(),
                        target.getMaxHealth() * 5.0F + 1.0F);
            } catch (Throwable ignored) {
            }
        }
        if (target.isRemoved()) return;

        // L3：确认死亡后立即 die，尽早启动死亡演出/掉落钩子（死亡事务未建立时才调用，防重复掉落）
        if (!TrueKillUtil.isDeathTransactionStarted(target) && target.getHealth() <= 0.0F) {
            try {
                target.die(source);
            } catch (Throwable ignored) {
            }
        }
        if (target.isRemoved()) return;

        TrueKillUtil.scheduleRetry(target, source, attacker);
    }

    /**
     * 供重试队列逐 tick 调用（看门狗）：
     * - 每 tick 重试 L0 归零锁定（防并发覆盖）；
     * - 死亡事务建立后不再重复调用 die（防重复掉落），仅维持血量归零；
     * - 管道磨血与 GENERIC_KILL 重试由队列按节拍驱动。
     */
    public static boolean retryTick(LivingEntity target, DamageSource source, LivingEntity attacker) {
        if (target.isRemoved()) return true;
        mark(target);
        clearDefenses(target);
        EntityHealthStateBridge.lockHealthAndCapToZero(target);

        if (!TrueKillUtil.isDeathTransactionStarted(target)) {
            try {
                target.die(source);
            } catch (Throwable ignored) {
            }
        }
        return target.isRemoved();
    }

    private static void clearDefenses(LivingEntity target) {
        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);
    }
}
