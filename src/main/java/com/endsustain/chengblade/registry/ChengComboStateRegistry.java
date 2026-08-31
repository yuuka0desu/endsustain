package com.endsustain.chengblade.registry;

import com.endsustain.chengblade.ChengBladeIntegration;
import com.endsustain.chengblade.ChengGameplayEvents;
import com.endsustain.chengblade.slasharts.MiaoqieSlashArts;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ChengComboStateRegistry {
    public static final DeferredRegister<ComboState> COMBO_STATES =
            DeferredRegister.create(ComboState.REGISTRY_KEY, ChengBladeIntegration.MOD_ID);

    private static final ResourceLocation MOTION = SlashBlade.prefix("combostate/motion");

    public static final RegistryObject<ComboState> MIAOQIE = COMBO_STATES.register(
            "miaoqie", () -> ComboState.Builder.newInstance()
                    .startAndEnd(0, 15)
                    .priority(92)
                    .speed(1.0F)
                    .motionLoc(MOTION)
                    .next(entity -> id("miaoqie_pause"))
                    .nextOfTimeout(entity -> SlashBlade.prefix("none"))
                    .addTickAction(ChengComboStateRegistry::advanceFromMain)
                    .addHitEffect(ChengGameplayEvents::onMiaoqieHit)
                    .clickAction(MiaoqieSlashArts::doMiaoqie)
                    .build());

    public static final RegistryObject<ComboState> MIAOQIE_PAUSE = COMBO_STATES.register(
            "miaoqie_pause", () -> ComboState.Builder.newInstance()
                    .startAndEnd(15, 25)
                    .priority(92)
                    .speed(0.1F)
                    .motionLoc(MOTION)
                    .next(entity -> id("miaoqie_burst"))
                    .nextOfTimeout(entity -> id("miaoqie_burst"))
                    .addTickAction(ChengComboStateRegistry::advanceFromPause)
                    .build());

    public static final RegistryObject<ComboState> MIAOQIE_BURST = COMBO_STATES.register(
            "miaoqie_burst", () -> ComboState.Builder.newInstance()
                    .startAndEnd(25, 30)
                    .priority(92)
                    .speed(2.0F)
                    .motionLoc(MOTION)
                    .nextOfTimeout(entity -> SlashBlade.prefix("none"))
                    .addTickAction(ChengComboStateRegistry::finishBurst)
                    .build());

    public static final RegistryObject<ComboState> HUNGRY_AMBUSH = COMBO_STATES.register(
            "hungry_ambush", () -> ComboState.Builder.newInstance()
                    .startAndEnd(0, 10)
                    .priority(96)
                    .speed(2.2F)
                    .motionLoc(MOTION)
                    .nextOfTimeout(entity -> SlashBlade.prefix("none"))
                    .clickAction(MiaoqieSlashArts::doHungryAmbush)
                    .build());

    private static ResourceLocation id(String path) {
        return new ResourceLocation(ChengBladeIntegration.MOD_ID, path);
    }

    private static void advanceFromMain(LivingEntity entity) {
        long elapsed = ComboState.getElapsed(entity);
        if (elapsed >= 15) {
            ChengGameplayEvents.updateCombo(entity, MIAOQIE_PAUSE.getId());
        }
    }

    private static void advanceFromPause(LivingEntity entity) {
        long elapsed = ComboState.getElapsed(entity);
        if (elapsed < 10) {
            entity.setDeltaMovement(entity.getDeltaMovement().multiply(0.15D, 1.0D, 0.15D));
        } else {
            ChengGameplayEvents.updateCombo(entity, MIAOQIE_BURST.getId());
        }
    }

    private static void finishBurst(LivingEntity entity) {
        if (ComboState.getElapsed(entity) >= 5) {
            ChengGameplayEvents.updateCombo(entity, SlashBlade.prefix("none"));
        }
    }

    private ChengComboStateRegistry() {
    }
}
