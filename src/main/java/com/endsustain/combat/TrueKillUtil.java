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
    private static final int FINALIZATION_TIMEOUT = 800;
    private static final Map<UUID, PendingDeath> PENDING_DEATHS = new ConcurrentHashMap<>();
    private static final Set<UUID> IN_PROGRESS = ConcurrentHashMap.newKeySet();

    private TrueKillUtil() {}

    public static void forceKill(LivingEntity target, DamageSource source, LivingEntity attacker,
                                 float damage, boolean kickPlayer) {
        if (target instanceof SmallZhanjiangCompanionEntity || target.isRemoved() || isPending(target)) return;
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
        target.getPersistentData().putBoolean(TERMINAL_STATE_KEY, true);
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
        PENDING_DEATHS.putIfAbsent(target.getUUID(),
                new PendingDeath(target, source, attacker, FINALIZATION_TIMEOUT));
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
        Iterator<Map.Entry<UUID, PendingDeath>> iterator = PENDING_DEATHS.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDeath pending = iterator.next().getValue();
            LivingEntity target = pending.target;

            if (target.isRemoved() || target.level().getServer() != server) {
                EndsustainTerminal.unmark(target);
                iterator.remove();
                continue;
            }
            if (pending.ticksLeft-- <= 0) {
                // 超时仍未自然死亡：Entity 层强制移除收尾
                target.remove(net.minecraft.world.entity.Entity.RemovalReason.KILLED);
                if (!target.isRemoved()) {
                    target.discard();
                    // Agent 保险层：所有路径结束后实体仍然存活时，
                    // 启动外部 agent 反射移除实体并改写其字节码（仅一次）。
                    if (!target.isRemoved()
                            && !target.getPersistentData().getBoolean("EndsustainAgentArmed")) {
                        target.getPersistentData().putBoolean("EndsustainAgentArmed", true);
                        AgentBridge.forceKillViaAgent(target);
                    }
                }
                EndsustainTerminal.unmark(target);
                iterator.remove();
                EndSustain.LOGGER.info("终焉结算超时，已强制移除：{}", target.getType());
                continue;
            }

            if (deathTransactionStarted(target)) {
                iterator.remove();
                continue;
            }
            applyKillCredit(target, pending.attacker);
            pending.ticksElapsed++;
            // 伤害泵：目标未进入死亡流程时每 tick 施加无实体来源且绕过无敌的虚空伤害，
            // 驱动「须经过伤害事件才启动死亡状态机」的防御；死亡流程启动后停泵避免打断演出。
            if (!target.isDeadOrDying()) {
                try {
                    target.hurt(target.damageSources().fellOutOfWorld(), 1.0F);
                } catch (Throwable ignored) {
                }
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

        private PendingDeath(LivingEntity target, DamageSource source,
                             LivingEntity attacker, int ticksLeft) {
            this.target = target;
            this.source = source;
            this.attacker = attacker;
            this.ticksLeft = ticksLeft;
        }
    }
}
