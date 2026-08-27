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
     * 终焉结算：伪造成玩家正常击杀的完整调用链。
     * 1) 标记并清障。
     * 2) 生命数据直写为存活值 1（原版 Float accessor）。
     * 3) hurt(playerAttack 源, MAX)——完整受伤链：生命写入被 mixin 强制归零，
     *    原版死亡分支触发 die()。
     * 4) 未立即移除时不再强制收尾：挂入重试队列，让目标自身的死亡推进
     *    （deathTime 演出、二次 die、掉落、分裂）自然完成。
     * 5) 重试队列超时仍未移除时才在 Entity 层强制移除。
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

        EntityHealthStateBridge.writeActiveHealth(target, 1.0F);

        try {
            target.hurt(source, Float.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        if (target.isRemoved()) return;

        zeroHealth(target);
        target.die(source);
        if (target.isRemoved()) return;

        TrueKillUtil.scheduleRetry(target, source, attacker);
    }

    /**
     * 供重试队列逐 tick 调用：维持死亡条件（血量归零、死亡入口触发），
     * 让目标自身的死亡推进与掉落自然发生；超时后由队列强制移除。
     */
    public static boolean retryTick(LivingEntity target, DamageSource source, LivingEntity attacker) {
        if (target.isRemoved()) return true;
        mark(target);
        clearDefenses(target);
        zeroHealth(target);

        try {
            target.die(source);
        } catch (Throwable ignored) {
        }
        return target.isRemoved();
    }

    private static void clearDefenses(LivingEntity target) {
        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);
    }

    private static void zeroHealth(LivingEntity target) {
        if (target.getHealth() > 0.0F) {
            target.setHealth(0.0F);
        }
        if (target.getHealth() > 0.0F) {
            EntityHealthStateBridge.zeroActiveHealth(target);
        }
    }
}
