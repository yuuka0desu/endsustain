package com.endsustain.mixin;

import com.endsustain.combat.TrueKillUtil;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Terminal damage enters the vanilla death transaction before any external
 * early-death or probability-revival hook can cancel it. The entity's own
 * LivingEntity/ServerPlayer die implementation still performs the real death.
 */
@Mixin(value = ForgeHooks.class, priority = 2000, remap = false)
public abstract class ForgeHooksTerminalDeathMixin {
    @Inject(method = "onLivingDeath", at = @At("HEAD"), cancellable = true, remap = false)
    private static void endsustain$beginTerminalDeath(LivingEntity entity, DamageSource source,
                                                      CallbackInfoReturnable<Boolean> cir) {
        if (entity.getPersistentData().getBoolean(TrueKillUtil.TERMINAL_STATE_KEY)) {
            cir.setReturnValue(false);
        }
    }
}
