package com.endsustain.combat;

import com.endsustain.EndSustain;
import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerCombatKillPacket;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TrueKillUtil {
    public static final String TERMINAL_STATE_KEY = "EndsustainTerminalState";
    private static final int NATURAL_TICK_DELAY = 1;
    private static final int FINALIZATION_TIMEOUT = 60;
    /** 审计 6.8-2：看门狗队列上限——P1「不放弃」使其成为硬约束，超限淘汰最旧条目并告警。 */
    private static final int MAX_PENDING = 64;
    private static final Map<UUID, PendingDeath> PENDING_DEATHS =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());
    private static final Set<UUID> IN_PROGRESS = ConcurrentHashMap.newKeySet();

    private TrueKillUtil() {}

    /**
     * 审计 P4-3：豁免白名单双校验——instanceof + 实体注册名命名空间 = endsustain。
     * 防止其他 Mod 把自己的实体继承成豁免类型绕过强杀链。
     */
    public static boolean isWhitelisted(LivingEntity target) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        boolean nativeNamespace = key != null && "endsustain".equals(key.getNamespace());
        return (target instanceof SmallZhanjiangCompanionEntity
                || target instanceof com.endsustain.entity.boss.FinaleEndsustainEntity)
                && nativeNamespace;
    }

    public static void forceKill(LivingEntity target, DamageSource source, LivingEntity attacker,
                                 float damage, boolean kickPlayer) {
        if (isWhitelisted(target) || EntityRawState.rawRemoved(target) || isPending(target)) return;
        UUID targetId = target.getUUID();
        if (!IN_PROGRESS.add(targetId)) return;
        try {
        if (target instanceof ServerPlayer player && kickPlayer
                && EndSustainConfig.KICK_PLAYERS_ON_OVERFLOW.get()) {
            if (player.connection != null) {
                player.connection.disconnect(Component.literal("终焉之刃的溢出伤害已将你驱逐。"));
            }
            return;
        }

        applyKillCredit(target, attacker);

        // 非玩家目标：交给本模组终焉结算 API。
        // 死亡逻辑（die）会正常执行，死亡掉落由原版死亡链调用；
        // 任何事件层防御都被 mixin 层拦截，最终由 Entity 层移除兜底。
        if (!(target instanceof ServerPlayer)) {
            EndsustainTerminal.terminate(target, source, attacker);
            return;
        }

        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);
        if (target.getHealth() > 0.0F) {
            target.setHealth(0.0F);
        }
        if (target.getHealth() > 0.0F) {
            EntityHealthStateBridge.zeroActiveHealth(target);
        }

        if (target instanceof ServerPlayer player) {
            // 打上终焉标记：mixin 层强制死亡入口与死亡掉落不可取消，
            // 保证未开 keepInventory 时物品按原版逻辑直接掉落地面。
            EndsustainTerminal.mark(target);
            try {
                target.die(source);
                PENDING_DEATHS.remove(target.getUUID());
                if (!deathTransactionStarted(target)) {
                    // 死亡被外部复活效果截断：兜底补一次地面掉落，再强制建立死亡事务。
                    dropInventoryOnGround(player);
                    forceServerPlayerDeathTransaction(player, source);
                }
                if (deathTransactionStarted(target)) {
                    EndSustain.LOGGER.info("终焉伤害已单次完成玩家死亡事务：{}，维度={}",
                            target.getType(), target.level().dimension().location());
                } else {
                    EndSustain.LOGGER.error("玩家死亡事务建立失败：{}，维度={}",
                            target.getType(), target.level().dimension().location());
                }
            } finally {
                EndsustainTerminal.unmark(target);
            }
        }
        } finally {
            IN_PROGRESS.remove(targetId);
        }
    }

    /**
     * 尾杀兜底掉落：玩家死亡事务被外部复活效果截断时，
     * 未开死亡不掉落（keepInventory）则把背包物品以物品实体直接掉落地面。
     * 消失诅咒物品与原版一致跳过；keepInventory 开启时物品保留在背包。
     */
    private static void dropInventoryOnGround(ServerPlayer player) {
        if (player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) return;
        Inventory inventory = player.getInventory();
        double x = player.getX();
        double y = player.getY();
        double z = player.getZ();
        boolean dropped = false;
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty() && !EnchantmentHelper.hasVanishingCurse(stack)) {
                ItemEntity item = new ItemEntity(player.level(), x, y, z, stack.copy());
                item.setDefaultPickUpDelay();
                player.level().addFreshEntity(item);
                inventory.setItem(i, ItemStack.EMPTY);
                dropped = true;
            }
        }
        if (dropped) {
            EndSustain.LOGGER.info("尾杀兜底掉落：玩家 {} 的物品已直接掉落地面",
                    player.getGameProfile().getName());
        }
    }

    /** 终焉结算未能在当前 tick 移除目标时，挂入逐 tick 重试队列。 */
    public static void scheduleRetry(LivingEntity target, DamageSource source, LivingEntity attacker) {
        synchronized (PENDING_DEATHS) {
            if (PENDING_DEATHS.size() >= MAX_PENDING
                    && !PENDING_DEATHS.containsKey(target.getUUID())) {
                // LRU 淘汰：移除最旧插入条目（淘汰即目标逃逸，必须显式告警）
                UUID eldest = PENDING_DEATHS.keySet().iterator().next();
                PendingDeath evicted = PENDING_DEATHS.remove(eldest);
                KillChainMonitor.log("QUEUE_OVERFLOW 淘汰=" + eldest
                        + " 类型=" + (evicted == null ? "?" : KillChainMonitor.typeName(evicted.target)));
                EndSustain.LOGGER.warn("[终焉强杀] 看门狗队列满（{}），已淘汰最旧条目：{}",
                        MAX_PENDING, evicted == null ? eldest : evicted.target.getType());
            }
            PENDING_DEATHS.putIfAbsent(target.getUUID(),
                    new PendingDeath(target, source, attacker, FINALIZATION_TIMEOUT));
        }
        EndSustain.LOGGER.info("终焉结算挂起：{} 存在额外防御，已进入逐 tick 强制结算队列",
                target.getType());
    }

    public static boolean isPending(LivingEntity target) {
        return PENDING_DEATHS.containsKey(target.getUUID());
    }

    public static void cancelPending(LivingEntity target) {
        PENDING_DEATHS.remove(target.getUUID());
        IN_PROGRESS.remove(target.getUUID());
    }

    /** 服务器停止时清理全部挂起状态，防止跨存档残留。 */
    public static void clearAll() {
        PENDING_DEATHS.clear();
        IN_PROGRESS.clear();
    }

    public static void tickPendingDeaths(MinecraftServer server) {
        synchronized (PENDING_DEATHS) {
            Iterator<Map.Entry<UUID, PendingDeath>> iterator = PENDING_DEATHS.entrySet().iterator();
            while (iterator.hasNext()) {
                PendingDeath pending = iterator.next().getValue();
                LivingEntity target = pending.target;

                if (EntityRawState.rawRemoved(target) || target.level().getServer() != server) {
                    EndsustainTerminal.unmark(target);
                    iterator.remove();
                    continue;
                }
                if (pending.ticksLeft-- <= 0) {
                    // 审计 P1：超时不再放弃——REMOVAL_PUMP 持续泵移除直至成功。
                    KillChainMonitor.logTargetState("TIMEOUT", target, "60t 超时，setRemoved 兜底");
                    // 掉落单次触发：整个强杀链只在超时兜底处主动掉落一次（dropped 标志），
                    // die 由看门狗单点调用；死亡字段建立后的后续 tick 不再触发任何掉落
                    if (!pending.dropped) {
                        pending.dropped = true;
                        try {
                            // dropAllDeathLoot 为 protected：反射双名调用（Mojmap/SRG）
                            java.lang.reflect.Method dlm = null;
                            for (String name : new String[]{"dropAllDeathLoot", "m_6668_"}) {
                                try {
                                    dlm = net.minecraft.world.entity.LivingEntity.class
                                            .getDeclaredMethod(name, DamageSource.class);
                                    break;
                                } catch (NoSuchMethodException ignored) {
                                }
                            }
                            if (dlm != null) {
                                dlm.setAccessible(true);
                                dlm.invoke(target, pending.source);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    // Agent 无条件启动：进入超时即派（首次 pumpTicks==0 必启动），
                    // 不依赖目标当前移除状态；之后每 100 tick 重试，总计 ≤3 次防风暴。
                    if (pending.agentAttempts < 3
                            && (pending.pumpTicks == 0 || pending.pumpTicks % 100 == 0)) {
                        pending.agentAttempts++;
                        KillChainMonitor.log("AGENT_LAUNCH 目标=" + KillChainMonitor.typeName(target)
                                + " 第" + pending.agentAttempts + "次");
                        AgentBridge.forceKillViaAgent(target);
                    }
                    EntityHealthStateBridge.startDeathAnimation(target);
                    try {
                        target.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                    } catch (Throwable ignored) {
                    }
                    if (!target.isRemoved() && target instanceof TerminalRemovalAccess access) {
                        try {
                            access.endsustain$forceRemoval(true);
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!target.isRemoved()) {
                        // 反射直写 removalReason 字段（双名 Mojmap/SRG）：不依赖任何 mixin
                        // 接口/虚方法——绕开一切 remove/setRemoved 覆写
                        try {
                            java.lang.reflect.Field rr = null;
                            for (String name : new String[]{"f_146795_", "removalReason"}) {
                                try {
                                    rr = net.minecraft.world.entity.Entity.class.getDeclaredField(name);
                                    break;
                                } catch (NoSuchFieldException ignored) {
                                }
                            }
                            if (rr != null) {
                                rr.setAccessible(true);
                                rr.set(target, net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                            }
                        } catch (Throwable ignored) {
                        }
                    }
                    if (!target.isRemoved()) {
                        try {
                            target.discard();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (EntityRawState.rawRemoved(target)) {
                        EndsustainTerminal.unmark(target);
                        iterator.remove();
                        continue;
                    }
                    if (++pending.pumpTicks % 20 == 0) {
                        if (target instanceof TerminalRemovalAccess access) {
                            try {
                                access.endsustain$forceRemoval(true);
                            } catch (Throwable ignored) {
                            }
                        }
                        try {
                            target.discard();
                        } catch (Throwable ignored) {
                        }
                    }
                    if (pending.pumpTicks % 100 == 0) {
                        KillChainMonitor.logTargetState("REMOVAL_PUMP", target,
                                "泵=" + pending.pumpTicks
                                        + " access=" + (target instanceof TerminalRemovalAccess)
                                        + " dead=" + target.isDeadOrDying());
                    }
                    continue;
                }

                if (deathTransactionStarted(target)) {
                    if (EntityRawState.rawRemoved(target)) {
                        EndsustainTerminal.unmark(target);
                        iterator.remove();
                        continue;
                    }
                    // 死亡事务已建立：无条件走完整流程——持续泵伤害 + 归零 +
                    // 死亡动画入口 + 手动推进 deathTime（实体 tick 被冻结时也驱动
                    // 原版死亡演出推进），直至实体自行移除或超时进入 REMOVAL_PUMP。
                    pending.ticksElapsed++;
                    KillChainMonitor.logTargetState("POST_DEATH_PUMP", target, "死亡事务已建立，持续泵伤害");
                    try {
                        target.hurt(target.damageSources().fellOutOfWorld(), 1.0F);
                    } catch (Throwable ignored) {
                    }
                    EntityHealthStateBridge.neutralize(target);
                    EntityHealthStateBridge.startDeathAnimation(target);
                    if (pending.ticksElapsed % 10 == 0) {
                        try {
                            target.discard();
                        } catch (Throwable ignored) {
                        }
                    }
                    continue;
                }
                applyKillCredit(target, pending.attacker);
                pending.ticksElapsed++;
                // die 单点调用：每目标仅一次（PendingDeath 标志），
                // 后续 tick 检测到死亡字段/标志后绝不重复调用——掉落只触发一次
                if (!deathTransactionStarted(target) && !pending.dieCalled) {
                    pending.dieCalled = true;
                    try {
                        target.die(pending.source);
                    } catch (Throwable ignored) {
                    }
                }
                // 伤害泵：无条件每 tick 施加虚空伤害（hurt 幂等，被取消也无害）
                try {
                    boolean pumped = target.hurt(target.damageSources().fellOutOfWorld(), 1.0F);
                    KillChainMonitor.logTargetState("DAMAGE_PUMP", target, "生效=" + pumped);
                } catch (Throwable ignored) {
                }
                // 管道磨血兜底：每 10 tick 一次合法大额伤害（防数据项写入被未知兼容层拦截的极端环境）
                if (pending.ticksElapsed % 10 == 0) {
                    try {
                        DamageSource grind = pending.attacker instanceof ServerPlayer player
                                ? target.damageSources().playerAttack(player)
                                : target.damageSources().generic();
                        target.hurt(grind, 99999.0F);
                    } catch (Throwable ignored) {
                    }
                }
                if (EndsustainTerminal.retryTick(target, pending.source, pending.attacker)) {
                    iterator.remove();
                }
            }
        }
    }

    private static void forceServerPlayerDeathTransaction(ServerPlayer player, DamageSource source) {
        if (player instanceof TerminalDeathStateAccess access) {
            access.endsustain$forceDeathTransaction();
        }
        Component message = source.getLocalizedDeathMessage(player);
        if (player.connection != null) {
            player.connection.send(new ClientboundPlayerCombatKillPacket(player.getId(), message));
        }
        player.level().broadcastEntityEvent(player, (byte) 3);
        player.gameEvent(GameEvent.ENTITY_DIE);
        EndSustain.LOGGER.warn("玩家死亡被外部复活效果截断，已强制建立死亡事务：玩家={}，维度={}",
                player.getGameProfile().getName(), player.level().dimension().location());
    }

    private static boolean deathTransactionStarted(LivingEntity target) {
        return target instanceof TerminalDeathStateAccess access
                && access.endsustain$deathTransactionStarted();
    }

    /** 死亡事务是否已建立（dead 标志已置位）：已建立时不可重复调用 die，避免重复掉落。 */
    public static boolean isDeathTransactionStarted(LivingEntity target) {
        return deathTransactionStarted(target);
    }

    private static void applyKillCredit(LivingEntity target, LivingEntity attacker) {
        if (attacker instanceof Player playerAttacker) {
            target.setLastHurtByPlayer(playerAttacker);
        }
    }

    private static final class PendingDeath {
        private final LivingEntity target;
        private final DamageSource source;
        private final LivingEntity attacker;
        private int ticksLeft;
        private int ticksElapsed;
        /** REMOVAL_PUMP 泵计数（超时后逐 tick 递增）。 */
        private int pumpTicks;
        /** Agent 启动次数（审计 P5：≤3，移入模组私有对象，目标不可接触）。 */
        private int agentAttempts;
        /** die 已调用（单点：每目标一次，防重复掉落）。 */
        private boolean dieCalled;
        /** 主动掉落已触发（单次）。 */
        private boolean dropped;

        private PendingDeath(LivingEntity target, DamageSource source,
                             LivingEntity attacker, int ticksLeft) {
            this.target = target;
            this.source = source;
            this.attacker = attacker;
            this.ticksLeft = ticksLeft;
        }
    }
}
