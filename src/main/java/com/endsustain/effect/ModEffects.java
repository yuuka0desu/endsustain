package com.endsustain.effect;

import com.endsustain.EndSustain;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ModEffects {
    public static final DeferredRegister<MobEffect> EFFECTS =
            DeferredRegister.create(ForgeRegistries.MOB_EFFECTS, EndSustain.MOD_ID);

    public static final RegistryObject<MobEffect> FINALE_PROTECTION = EFFECTS.register("finale_protection",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0x7E35D6));
    public static final RegistryObject<MobEffect> FINALE_BLESSING = EFFECTS.register("finale_blessing",
            () -> new SimpleEffect(MobEffectCategory.BENEFICIAL, 0xD76CFF));
    public static final RegistryObject<MobEffect> FINALE_CURSE = EFFECTS.register("finale_curse",
            () -> new FinaleCurseEffect());

    public static void register(IEventBus bus) { EFFECTS.register(bus); }

    private static class SimpleEffect extends MobEffect {
        protected SimpleEffect(MobEffectCategory category, int color) { super(category, color); }
    }

    private static final class FinaleCurseEffect extends MobEffect {
        private FinaleCurseEffect() {
            super(MobEffectCategory.HARMFUL, 0x5A176F);
            addAttributeModifier(Attributes.MAX_HEALTH, "75c5cd3b-b83f-4f42-a128-b01ef953a70b",
                    -0.20D, AttributeModifier.Operation.MULTIPLY_TOTAL);
        }

        @Override
        public double getAttributeModifierValue(int amplifier, AttributeModifier modifier) {
            int level = amplifier + 1;
            return -Math.min(0.20D + 0.10D * (level - 1), 0.50D);
        }
    }
}
