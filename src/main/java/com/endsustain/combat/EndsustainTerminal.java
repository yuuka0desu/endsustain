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
    /** Agent 优先级标记键：Agent attach 后写入 wall-clock 时间戳，主线程让位窗口内不改目标。 */
    public static final String AGENT_PRIORITY_KEY = "EndsustainAgentPriority";
    /** 主线程让位窗口：Agent 在窗口内完成四级链；超时视为 Agent 失联，主线程恢复接管。 */
    private static final long AGENT_PRIORITY_WINDOW_MS = 5000L;

    private EndsustainTerminal() {}

    /**
     * 优先级仲裁（审计 P1 补强）：Agent 线程的修改优先于主线程。
     * 返回 true 表示 Agent 已接管且仍在活跃窗口内——主线程应让位
     * （跳过归零/泵/移除修改，仅轮询移除结果）。
     * 窗口超时（Agent attach 后崩溃/失联）自动清除标记并恢复主线程接管。
     */
    public static boolean agentHasPriority(LivingEntity target) {
        long stamp = target.getPersistentData().getLong(AGENT_PRIORITY_KEY);
        if (stamp <= 0L) return false;
        if (System.currentTimeMillis() - stamp > AGENT_PRIORITY_WINDOW_MS) {
            target.getPersistentData().remove(AGENT_PRIORITY_KEY);
            return false;
        }
        return true;
    }

    /** 给目标打上终焉标记：此后任何回血/正数生命写入都会被本模组 mixin 拦截。 */
    public static void mark(LivingEntity target) {
        // 权威判定在模组私有表（TerminalStateRegistry）；persistentData 键仅作影子/诊断
        TerminalStateRegistry.mark(target);
        target.getPersistentData().putBoolean(TERMINAL_KEY, true);
        // 标记即无条件启用 Agent 层（每目标一次，attach 约 1-2 秒后并行生效）：
        // 写槽 → 读拦截 retransform → 判定重写 → 破壳直写 → 硬移除 全链随标记启动，
        // 不再等待 300t 超时。玩家目标除外（Agent 硬移除玩家无意义）。
        if (!(target instanceof net.minecraft.server.level.ServerPlayer)
                && TerminalStateRegistry.armAgentOnce(target)) {
            KillChainMonitor.log("AGENT_LAUNCH 标记即启用 目标=" + KillChainMonitor.typeName(target));
            AgentBridge.forceKillViaAgent(target);
        }
    }

    public static boolean isMarked(LivingEntity target) {
        return TerminalStateRegistry.isMarked(target);
    }

    public static void unmark(LivingEntity target) {
        TerminalStateRegistry.unmark(target.getUUID());
        target.getPersistentData().remove(TERMINAL_KEY);
        target.getPersistentData().remove(HEALTH_HIJACK_KEY);
        target.getPersistentData().remove(AGENT_PRIORITY_KEY);
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
