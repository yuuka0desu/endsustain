package com.endsustain.pigblade;

import com.endsustain.EndSustain;
import com.endsustain.pigblade.registry.PigBladeRegistry;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * 末影猪咪 蘸酱 —— 战斗事件：
 * 幻影剑为原模组实体，命中与命中后行为统一由 PigSwordTracker 结算
 * （伤害 → 猪化 / Boss 仆从召唤 / 负面状态 / 猪音效 / 0.1% 附加伤害）。
 * 本类另负责：猪形态受伤 +50%、被击杀还原、掉落屏蔽、仆从转化与到期。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID)
public final class PigBladeEvents {
    private PigBladeEvents() {
    }

    @SubscribeEvent
    public static void onDoingSlash(SlashBladeEvent.DoSlashEvent event) {
        if (!(event.getUser() instanceof Player player) || player.level().isClientSide()) return;
        ISlashBladeState state = event.getSlashBladeState();
        if (state == null) return;

        if (state.hasSpecialEffect(PigBladeRegistry.YOU_ARE_PIG.getId())
                && SpecialEffect.isEffective(PigBladeRegistry.YOU_ARE_PIG.get(), player.experienceLevel)) {
            // 幻影剑沿视线方向发射（roll 仅为原版剑气视觉旋转，不参与方向计算），
            // 命中后行为由 PigSwordTracker 统一结算
            Vec3 dir = player.getLookAngle();
            PigBladeLogic.launch(player, dir, Math.max(1.0D, event.getDamage() * 0.8D));
        } else {
            com.endsustain.EndSustain.LOGGER.info("[猪咪挥刀] SE 未生效：hasSE={}，等级={}",
                    state.hasSpecialEffect(PigBladeRegistry.YOU_ARE_PIG.getId()),
                    player.experienceLevel);
        }
    }

    /** 幻影剑碰撞结算：每服务端 tick 检测并执行命中行为。 */
    @SubscribeEvent
    public static void onServerTick(net.minecraftforge.event.TickEvent.ServerTickEvent event) {
        if (event.phase == net.minecraftforge.event.TickEvent.Phase.END) {
            PigSwordTracker.tick();
        }
    }

    /** 猪化生物抗性 -50%：受到的伤害提高 50%。 */
    @SubscribeEvent
    public static void onLivingHurt(net.minecraftforge.event.entity.living.LivingHurtEvent event) {
        if (event.getEntity() instanceof PigFormPig) {
            event.setAmount(event.getAmount() * 1.5F);
        }
    }

    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof PigFormPig pig) {
            PigFormLogic.revert(pig, false);
        }
    }

    /** 原始猪咪仆从攻击命中时尝试转化目标。 */
    @SubscribeEvent
    public static void onMinionHurt(LivingHurtEvent event) {
        if (event.getSource().getEntity() instanceof LivingEntity attacker
                && PigMinionLogic.isOriginalMinion(attacker)
                && attacker.getPersistentData().getInt(PigMinionLogic.CONVERTS_KEY) > 0) {
            PigMinionLogic.tryConvert(attacker, event.getEntity());
        }
    }

    /** 猪咪仆从到期移除与目标维持。 */
    @SubscribeEvent
    public static void onLivingTick(net.minecraftforge.event.entity.living.LivingEvent.LivingTickEvent event) {
        if (event.getEntity().getPersistentData().getBoolean(PigMinionLogic.MINION_KEY)) {
            PigMinionLogic.tickMinion(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLivingDrops(LivingDropsEvent event) {
        // 猪形态替身不产生掉落：奖励由还原后的原实体结算
        if (event.getEntity() instanceof PigFormPig) {
            event.setCanceled(true);
        }
    }
}
