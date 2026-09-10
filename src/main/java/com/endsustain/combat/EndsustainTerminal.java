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
    /** getHealth 劫持开关：neutralize 完成后启用，mixin 令 getHealth 恒返回 0。 */
    public static final String HEALTH_HIJACK_KEY = "EndsustainHealthHijacked";

    private EndsustainTerminal() {}

    /** 给目标打上终焉标记：此后任何回血/正数生命写入都会被本模组 mixin 拦截。 */
    public static void mark(LivingEntity target) {
        // 权威判定在模组私有表（TerminalStateRegistry）；persistentData 键仅作影子/诊断
        TerminalStateRegistry.mark(target);
        target.getPersistentData().putBoolean(TERMINAL_KEY, true);
    }

    public static boolean isMarked(LivingEntity target) {
        return TerminalStateRegistry.isMarked(target);
    }

    public static void unmark(LivingEntity target) {
        TerminalStateRegistry.unmark(target.getUUID());
        target.getPersistentData().remove(TERMINAL_KEY);
        target.getPersistentData().remove(HEALTH_HIJACK_KEY);
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
        // 白名单双校验：注册名命名空间非 endsustain 的伪装实体不走豁免分支。
        if (target instanceof com.endsustain.entity.boss.FinaleEndsustainEntity boss
                && TrueKillUtil.isWhitelisted(boss)) {
            boss.beginCombatDeathTail(source);
            return;
        }

        // L0：四层通用无效化（数值生命/上限锁定、字符串校验码生命、字节标志、persistentData 浮点）
        boolean neutralized = EntityHealthStateBridge.neutralize(target);
        com.endsustain.EndSustain.LOGGER.info("[终焉强杀] L0 无效化：目标={}，写入={}，血量={}/{}, dead={}",
                target.getType(), neutralized, target.getHealth(), target.getMaxHealth(),
                target.isDeadOrDying());
        KillChainMonitor.logTargetState("L0", target, "neutralize写入=" + neutralized);
        if (target.isRemoved()) return;

        // 五层归零完成后启用 getHealth 劫持：mixin 令 getHealth 恒返回 0，
        // 实体的回滚/回血逻辑读到的生命恒为 0，死亡状态机必然接管。
        TerminalStateRegistry.hijack(target);
        target.getPersistentData().putBoolean(HEALTH_HIJACK_KEY, true);
        KillChainMonitor.log("HIJACK_ON getHealth 劫持启用");

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

        // L3：无条件调用 die——启动死亡演出/掉落钩子（死亡事务未建立时）。
        // 不再依赖 getHealth<=0 或 deathTime==0 前置条件：标记实体无论其
        // 生命读数/劫持是否生效，一律进入死亡链，后续由看门狗持续兜底。
        if (!TrueKillUtil.isDeathTransactionStarted(target)) {
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
        // 每 tick 四层归零：任何回滚/回血/纠正都敌不过下一 tick 的重新归零
        boolean neutralized = EntityHealthStateBridge.neutralize(target);
        KillChainMonitor.logTargetState("WATCHDOG_TICK", target,
                "neutralize写入=" + neutralized + " 死亡事务="
                        + TrueKillUtil.isDeathTransactionStarted(target)
                        + " registry=" + TerminalStateRegistry.isMarked(target)
                        + "/" + TerminalStateRegistry.isHijacked(target));

        // 无条件调用 die（死亡事务未建立时）：不依赖 deathTime/生命读数
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
