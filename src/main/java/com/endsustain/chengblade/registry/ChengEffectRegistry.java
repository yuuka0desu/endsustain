package com.endsustain.chengblade.registry;

import com.endsustain.chengblade.ChengBladeIntegration;
import com.endsustain.chengblade.specialeffects.BeingStirFriedEffect;
import com.endsustain.chengblade.specialeffects.DeliciousEffect;
import net.minecraft.world.effect.MobEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ChengEffectRegistry {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, ChengBladeIntegration.MOD_ID);

    public static final RegistryObject<MobEffect> BEING_STIR_FRIED = EFFECTS.register(
            "being_stir_fried", () -> new BeingStirFriedEffect(0xE8892A));

    public static final RegistryObject<MobEffect> DELICIOUS = EFFECTS.register(
            "delicious", () -> new DeliciousEffect(0xFF8A5A));

    private ChengEffectRegistry() {
    }
}
