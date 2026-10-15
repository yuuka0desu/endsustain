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
    /** 终焉标记：实体 Tag（非 persistentData 状态键）。权威判定仍在 TerminalStateRegistry。 */
    public static final String TERMINAL_TAG = "endsustain_terminal";
    /** getHealth 劫持开关 Tag：mixin 按双 Tag 判定（纯 MC 类调用）。 */
    public static final String HIJACK_TAG = "endsustain_hijacked";
    public static final String TERMINAL_KEY = "EndsustainTerminalState";
    /** getHealth 劫持开关：Registry 权威；persistentData 键仅作旧版本兼容影子。 */
    public static final String HEALTH_HIJACK_KEY = "EndsustainHealthHijacked";

    private EndsustainTerminal() {}

    /** 给目标打上终焉标记（Tag + 私有表双写）：此后任何回血/正数生命写入都会被本模组 mixin 拦截。 */
    public static void mark(LivingEntity target) {
        // 权威判定在模组私有表（TerminalStateRegistry），Tag 为 mixin 判定形态
        TerminalStateRegistry.mark(target);
        try {
            if (!target.getTags().contains(TERMINAL_TAG)) {
                target.addTag(TERMINAL_TAG);
            }
        } catch (Throwable ignored) {
        }
        // 标记即无条件启用 Agent 层（每目标一次）：
        // 1) AgentInline 内联通道——JVM 内直接执行（零外部依赖）：
        //    写槽/锚点清空/破壳直写，bin/java.exe 缺失、attach 被禁等限制下同样生效；
        // 2) AgentBridge 外部通道——retransform 读拦截/判定重写 + 硬移除增强（java 可用时）。
        // 玩家目标除外（Agent 对玩家实体无意义）。
        if (!(target instanceof net.minecraft.server.level.ServerPlayer)
                && TerminalStateRegistry.armAgentOnce(target)) {
            boolean inlineHit = AgentInline.runInline(target);
            KillChainMonitor.log("AGENT_LAYER 标记即启用 目标=" + KillChainMonitor.typeName(target)
                    + " inline=" + inlineHit);
            AgentBridge.forceKillViaAgent(target);
        }
    }

    /** 启用 getHealth 劫持：加 hijack Tag（mixin 判定），Registry 同步。 */
    public static void hijack(LivingEntity target) {
        TerminalStateRegistry.hijack(target);
        try {
            if (!target.getTags().contains(HIJACK_TAG)) {
                target.addTag(HIJACK_TAG);
            }
        } catch (Throwable ignored) {
        }
    }

    public static boolean isMarked(LivingEntity target) {
        return TerminalStateRegistry.isMarked(target);
    }

    public static void unmark(LivingEntity target) {
        TerminalStateRegistry.unmark(target.getUUID());
        try {
            target.removeTag(TERMINAL_TAG);
            target.removeTag(HIJACK_TAG);
        } catch (Throwable ignored) {
        }
        // 旧版本持久键残留清理（兼容历史版本标记）
        try {
            target.getPersistentData().remove(TERMINAL_KEY);
            target.getPersistentData().remove(HEALTH_HIJACK_KEY);
        } catch (Throwable ignored) {
        }
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
        // hurt 准入闸门先于管道伤害绕过：否则目标自身的伤害钳制会把
        // Float.MAX_VALUE 压回小值，并把本次调用计入「非法伤害」累计。
        try {
            DeathAxisResolver.bypassHurtGates(target);
        } catch (Throwable ignored) {
        }

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
        if (EntityRawState.rawRemoved(target)) return;
        // 五层归零完成后启用 getHealth 劫持：mixin 令 getHealth 恒返回 0，
        // 实体的回滚/回血逻辑读到的生命恒为 0，死亡状态机必然接管。
        hijack(target);
        KillChainMonitor.log("HIJACK_ON getHealth 劫持启用");

        // L1：管道 hurt（完整受伤链；生命写入由 mixin 强制归零，原版死亡分支触发 die）
        try {
            target.hurt(source, Float.MAX_VALUE);
        } catch (Throwable ignored) {
        }
        if (EntityRawState.rawRemoved(target)) return;

        // L2：GENERIC_KILL 内置退场兜底（无攻击者、伤害超过 5 倍最大生命）
        if (!TrueKillUtil.isDeathTransactionStarted(target)) {
            try {
                target.hurt(target.damageSources().genericKill(),
                        target.getMaxHealth() * 5.0F + 1.0F);
            } catch (Throwable ignored) {
            }
        }
        if (EntityRawState.rawRemoved(target)) return;

        // L3 起挂入看门狗：die 由看门狗单点调用（PendingDeath.dieCalled 标志，
        // 每目标一次——掉落只触发一次，后续 tick 检测到死亡字段不再重复触发）
        if (EntityRawState.rawRemoved(target)) return;

        TrueKillUtil.scheduleRetry(target, source, attacker);
    }

    /**
     * 供重试队列逐 tick 调用（看门狗）：
     * - 每 tick 重试 L0 归零锁定（防并发覆盖）；
     * - 死亡事务建立后不再重复调用 die（防重复掉落），仅维持血量归零；
     * - 管道磨血与 GENERIC_KILL 重试由队列按节拍驱动。
     */
    public static boolean retryTick(LivingEntity target, DamageSource source, LivingEntity attacker) {
        if (EntityRawState.rawRemoved(target)) return true;
        mark(target);
        clearDefenses(target);
        // 每 tick 重试闸门绕过：目标侧可能在其它路径重置冷却计数器
        try {
            DeathAxisResolver.bypassHurtGates(target);
        } catch (Throwable ignored) {
        }
        // 每 tick 四层归零：任何回滚/回血/纠正都敌不过下一 tick 的重新归零
        boolean neutralized = EntityHealthStateBridge.neutralize(target);
        // 独立判定轴同样每 tick 压制：目标侧 init/校验可能把判定字段恢复成满值
        boolean collapsed = false;
        try {
            if (DeathAxisResolver.probe(target) == DeathAxisResolver.Probe.HIT) {
                collapsed = DeathAxisResolver.collapse(target);
            }
        } catch (Throwable ignored) {
        }
        KillChainMonitor.logTargetState("WATCHDOG_TICK", target,
                "neutralize写入=" + neutralized + " 判定轴归零=" + collapsed + " 死亡事务="
                        + TrueKillUtil.isDeathTransactionStarted(target)
                        + " registry=" + TerminalStateRegistry.isMarked(target)
                        + "/" + TerminalStateRegistry.isHijacked(target));

        // die 由 tickPendingDeaths 单点调用（PendingDeath.dieCalled 保护），
        // 此处不再重复调用——掉落只触发一次
        return EntityRawState.rawRemoved(target);
    }

    private static void clearDefenses(LivingEntity target) {
        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);
    }
}
