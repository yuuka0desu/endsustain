package com.endsustain.chengblade;

import com.endsustain.EndSustain;
import com.endsustain.chengblade.registry.ChengComboStateRegistry;
import com.endsustain.chengblade.registry.ChengEffectRegistry;
import com.endsustain.chengblade.registry.ChengSpecialEffectsRegistry;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.event.SlashBladeEvent;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.RegistryObject;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID)
public final class ChengGameplayEvents {
    public static final String CHENG_IMMUNE_KEY = "cheng_immune_until";
    private static final int EFFECT_REFRESH_INTERVAL = 20;
    private static final int EFFECT_REFRESH_DURATION = 40;
    private static final int DELICIOUS_DURATION = 200;
    private static final int DELICIOUS_MAX_STACKS = 50;

    private ChengGameplayEvents() {
    }

    public static void updateCombo(LivingEntity entity, ResourceLocation combo) {
        entity.getMainHandItem().getCapability(ItemSlashBlade.BLADESTATE).ifPresent(state -> {
            state.updateComboSeq(entity, combo);
            state.sendChanges(entity);
        });
    }

    public static void onMiaoqieHit(LivingEntity user, LivingEntity target) {
        if (!(user instanceof Player) || user.level().isClientSide()) {
            return;
        }
        if (user.getRandom().nextFloat() < 0.25F) {
            updateCombo(user, ChengComboStateRegistry.HUNGRY_AMBUSH.getId());
        }
    }

    public static boolean isSeActive(Player player, RegistryObject<SpecialEffect> se) {
        ISlashBladeState state = player.getMainHandItem()
                .getCapability(ItemSlashBlade.BLADESTATE).orElse(null);
        if (state == null || !state.hasSpecialEffect(se.getId())) {
            return false;
        }
        return SpecialEffect.isEffective(se.getId(), player.experienceLevel);
    }

    public static boolean isHungryMeowActive(Player player) {
        return isSeActive(player, ChengSpecialEffectsRegistry.HEART_PALPITATIONS);
    }

    @SubscribeEvent
    public static void onLivingAttack(LivingAttackEvent event) {
        if (isImmune(event.getEntity())) {
            event.setCanceled(true);
            return;
        }
        // 已有“好吃”层数的目标，任意攻击可再叠一层
        if (event.getSource().getEntity() instanceof LivingEntity attacker
                && attacker != event.getEntity()
                && event.getEntity().getEffect(ChengEffectRegistry.DELICIOUS.get()) != null) {
            applyDeliciousStack(event.getEntity());
        }
    }

    @SubscribeEvent
    public static void onLivingDamage(LivingDamageEvent event) {
        if (isImmune(event.getEntity())) {
            event.setCanceled(true);
            return;
        }

        // 好吃：每层易伤 4%
        MobEffectInstance delicious = event.getEntity().getEffect(ChengEffectRegistry.DELICIOUS.get());
        if (delicious != null) {
            int stacks = delicious.getAmplifier() + 1;
            event.setAmount(event.getAmount() * (1.0F + 0.04F * stacks));
        }

        // 我喜欢你喵：自身受到的伤害 ×0.7
        if (event.getEntity() instanceof Player victim
                && isSeActive(victim, ChengSpecialEffectsRegistry.LOVE_MEOW)) {
            event.setAmount(event.getAmount() * 0.7F);
        }

        LivingEntity attacker = event.getSource().getEntity() instanceof LivingEntity living ? living : null;
        if (!(attacker instanceof Player player)) {
            return;
        }

        // 我喜欢你喵：自身造成的伤害 ×1.7
        if (isSeActive(player, ChengSpecialEffectsRegistry.LOVE_MEOW)) {
            event.setAmount(event.getAmount() * 1.7F);
        }

        if (!isHungryMeowActive(player)) {
            return;
        }
        // 好饿喵：生命值高于 50 的部分 x 形成独立乘区 a = 1 + 0.1x
        double x = player.getHealth() - 50.0D;
        double a = x > 0.0D ? 1.0D + 0.1D * x : 1.0D;
        float amount = event.getAmount();
        amount *= (float) a;
        // 深夜加成：+10a%
        long time = Math.floorMod(player.level().getDayTime(), 24000L);
        if (time >= 23000L || time < 6000L) {
            amount *= (float) (1.0D + 0.10D * a);
        }
        event.setAmount(amount);
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.side != LogicalSide.SERVER) {
            return;
        }
        Player player = event.player;
        if (player.tickCount % EFFECT_REFRESH_INTERVAL != 0) {
            return;
        }
        if (isHungryMeowActive(player)) {
            player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, EFFECT_REFRESH_DURATION, 0, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.DOLPHINS_GRACE, EFFECT_REFRESH_DURATION, 0, false, true));
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER, EFFECT_REFRESH_DURATION, 0, false, true));
        }
        if (isSeActive(player, ChengSpecialEffectsRegistry.LOVE_MEOW)) {
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, EFFECT_REFRESH_DURATION, 0, false, true));
        }
    }

    private static boolean isImmune(LivingEntity entity) {
        return entity.getPersistentData().getLong(CHENG_IMMUNE_KEY) > entity.level().getGameTime();
    }

    public static void applyDeliciousStack(LivingEntity target) {
        MobEffectInstance existing = target.getEffect(ChengEffectRegistry.DELICIOUS.get());
        int stacks = existing == null
                ? 1
                : Math.min(existing.getAmplifier() + 2, DELICIOUS_MAX_STACKS);
        target.addEffect(new MobEffectInstance(
                ChengEffectRegistry.DELICIOUS.get(), DELICIOUS_DURATION, stacks - 1, false, true, true));
    }

    public static void applyFirstDelicious(LivingEntity target) {
        if (target.getEffect(ChengEffectRegistry.DELICIOUS.get()) == null) {
            applyDeliciousStack(target);
        }
    }
}
