package com.endsustain.chengblade.registry;

import com.endsustain.chengblade.ChengBladeIntegration;
import com.endsustain.chengblade.specialeffects.HeartPalpitationsEffect;
import com.endsustain.chengblade.specialeffects.LoveMeowEffect;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ChengSpecialEffectsRegistry {
    public static final DeferredRegister<SpecialEffect> SPECIAL_EFFECTS =
            DeferredRegister.create(SpecialEffect.REGISTRY_KEY, ChengBladeIntegration.MOD_ID);

    public static final RegistryObject<SpecialEffect> HEART_PALPITATIONS = SPECIAL_EFFECTS.register(
            "heart_palpitations", HeartPalpitationsEffect::new);

    public static final RegistryObject<SpecialEffect> LOVE_MEOW = SPECIAL_EFFECTS.register(
            "love_meow", LoveMeowEffect::new);

    private ChengSpecialEffectsRegistry() {
    }
}
