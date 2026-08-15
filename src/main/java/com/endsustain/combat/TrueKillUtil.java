package com.endsustain.combat;

import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class TrueKillUtil {
    private TrueKillUtil() {}

    public static void forceKill(LivingEntity target, DamageSource source, LivingEntity attacker,
                                 float damage, boolean kickPlayer) {
        if (target instanceof SmallZhanjiangCompanionEntity) return;
        if (target instanceof ServerPlayer player && kickPlayer
                && EndSustainConfig.KICK_PLAYERS_ON_OVERFLOW.get()) {
            player.connection.disconnect(Component.literal("终焉之刃的溢出伤害已将你驱逐。"));
            return;
        }

        // 写入玩家击杀归属，使 Boss 战利品表、经验和进度能够正常结算。
        if (attacker instanceof Player playerAttacker) {
            target.setLastHurtByPlayer(playerAttacker);
        }

        target.setInvulnerable(false);
        target.invulnerableTime = 0;
        target.setAbsorptionAmount(0.0F);
        // 兼容实现了通用真杀协议的保护系统：标记伤害源并调用其底层生命写入器。
        if (invokeExternalTrueKillProtocol(target, source)) {
            // 外部保护框架已经接管真实生命与死亡演出，继续 remove 会跳过其 tickDeath 和掉落结算。
            return;
        }
        target.hurt(source, damage);
        target.invulnerableTime = 0;
        if (target.isAlive()) target.setHealth(0.0F);
        // 先走模组正常死亡入口，保留战利品、经验和死亡事件。
        if (!target.isDeadOrDying()) target.die(source);
        target.invulnerableTime = 0;
        // 保留实体自身的 tickDeath：Boss 动画、战利品、经验和任务事件都在该阶段结算。
    }

    private static boolean invokeExternalTrueKillProtocol(LivingEntity target, DamageSource source) {
        try {
            Class<?> damageInterface = Class.forName("com.mega.revelationfix.safe.DamageSourceInterface");
            if (!damageInterface.isInstance(source)) return false;
            damageInterface.getMethod("revelationfix$trueKill", boolean.class).invoke(source, true);
            Class<?> actualHurt = Class.forName("com.mega.revelationfix.util.entity.EntityActuallyHurt");
            float healthBefore = Math.max(1.0F, target.getHealth());
            Object handler = actualHurt.getConstructor(LivingEntity.class).newInstance(target);
            actualHurt.getMethod("actuallyHurt", DamageSource.class, float.class, boolean.class)
                    .invoke(handler, source, Float.MAX_VALUE, true);
            boolean deathStarted = target.isRemoved() || target.isDeadOrDying();
            if (!deathStarted && target.getHealth() <= 0.0F) {
                float restored = Math.min(healthBefore, Math.max(1.0F, target.getMaxHealth()));
                // 不会进入死亡流程的训练/技术实体不得遗留负生命；同时恢复框架识别到的真实生命字段。
                actualHurt.getMethod("catchSetTrueHealth", LivingEntity.class, float.class)
                        .invoke(null, target, restored);
                target.setHealth(restored);
                target.invulnerableTime = 0;
                com.endsustain.EndSustain.LOGGER.info("终焉强杀目标 {} 拒绝进入死亡状态，生命已从 {} 恢复为 {}",
                        target.getType(), target.getHealth(), restored);
                return true;
            }
            com.endsustain.EndSustain.LOGGER.info("终焉强杀已通过通用真实生命协议处理 {}，剩余生命={}，死亡状态={}",
                    target.getType(), target.getHealth(), deathStarted);
            return true;
        } catch (ClassNotFoundException ignored) {
            // 未安装该通用保护框架时继续使用原版强杀路径。
            return false;
        } catch (ReflectiveOperationException exception) {
            com.endsustain.EndSustain.LOGGER.warn("调用外部通用真杀协议失败，将使用原版死亡流程", exception);
            return false;
        }
    }
}

