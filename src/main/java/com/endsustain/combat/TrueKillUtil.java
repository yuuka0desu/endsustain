package com.endsustain.combat;

import com.endsustain.EndSustain;
import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import net.minecraft.network.chat.Component;
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
    private static final int FINALIZATION_TIMEOUT = 120;
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
            player.connection.disconnect(Component.literal("终焉之刃的溢出伤害已将你驱逐。"));
            return;
        }

        applyKillCredit(target, attacker);
        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);

        // Non-player entities keep a normal hurt callback before terminal resolution.
        // Players use one explicit death entry below, avoiding a first death from hurt()
        // followed by another death from die().
        if (!(target instanceof ServerPlayer)) {
            try {
                target.hurt(source, damage);
            } catch (ClassCastException incompatibleHealthValue) {
                EndSustain.LOGGER.warn("目标 {} 的外部生命读取类型不兼容，直接进入通用终止状态",
                        target.getType());
            }
        }
        target.invulnerableTime = 0;
        if (target.isRemoved()) return;

        target.getPersistentData().putBoolean(TERMINAL_STATE_KEY, true);
        if (target.getHealth() > 0.0F) {
            target.setHealth(0.0F);
        }
        if (target.getHealth() > 0.0F) {
            EntityHealthStateBridge.zeroActiveHealth(target);
        }

        if (target instanceof ServerPlayer) {
            target.die(source);
            PENDING_DEATHS.remove(target.getUUID());
            if (deathTransactionStarted(target)) {
                EndSustain.LOGGER.info("终焉伤害已单次完成玩家死亡事务：{}", target.getType());
            } else {
                EndSustain.LOGGER.error("玩家死亡事务未建立，已停止重复调用：{}", target.getType());
            }
            return;
        }

        if (target.getHealth() <= 0.0F) {
            // Players and ordinary entities should enter their real death transaction immediately.
            // Delayed/custom bosses that reject this first call remain in the retry queue below.
            target.die(source);
            if (target.isRemoved()) {
                target.getPersistentData().remove(TERMINAL_STATE_KEY);
                return;
            }
            if (deathTransactionStarted(target)) {
                EndSustain.LOGGER.info("终焉伤害已建立目标的真实死亡事务：{}", target.getType());
                return;
            }
            PENDING_DEATHS.putIfAbsent(target.getUUID(),
                    new PendingDeath(target, source, attacker, FINALIZATION_TIMEOUT));
            EndSustain.LOGGER.info("终焉伤害已同步目标生命状态，等待实体自身完成死亡：{}",
                    target.getType());
        } else {
            EndSustain.LOGGER.warn("终焉伤害未找到目标 {} 当前使用的生命同步状态，剩余生命={}",
                    target.getType(), target.getHealth());
        }
        } finally {
            IN_PROGRESS.remove(targetId);
        }
    }

    public static boolean isPending(LivingEntity target) {
        return PENDING_DEATHS.containsKey(target.getUUID());
    }

    public static void tickPendingDeaths(MinecraftServer server) {
        Iterator<Map.Entry<UUID, PendingDeath>> iterator = PENDING_DEATHS.entrySet().iterator();
        while (iterator.hasNext()) {
            PendingDeath pending = iterator.next().getValue();
            LivingEntity target = pending.target;

            if (target.isRemoved() || target.level().getServer() != server || pending.ticksLeft-- <= 0) {
                target.getPersistentData().remove(TERMINAL_STATE_KEY);
                iterator.remove();
                continue;
            }

            target.getPersistentData().putBoolean(TERMINAL_STATE_KEY, true);
            if (deathTransactionStarted(target)) {
                iterator.remove();
                continue;
            }
            applyKillCredit(target, pending.attacker);
            target.setInvulnerable(false);
            target.invulnerableTime = 0;
            target.setAbsorptionAmount(0.0F);

            if (target.getHealth() > 0.0F) {
                target.setHealth(0.0F);
                if (target.getHealth() > 0.0F) {
                    EntityHealthStateBridge.zeroActiveHealth(target);
                }
            }

            // Give custom entities one natural entity tick before asking their own public death entry to continue.
            if (pending.ticksElapsed++ < NATURAL_TICK_DELAY || target.getHealth() > 0.0F) continue;

            target.die(pending.source);
            if (deathTransactionStarted(target)) {
                iterator.remove();
            }
        }
    }

    private static boolean deathTransactionStarted(LivingEntity target) {
        return target instanceof TerminalDeathStateAccess access
                && access.endsustain$deathTransactionStarted();
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
