package com.endsustain.mixin;

import com.endsustain.combat.TerminalDeathStateAccess;
import com.endsustain.combat.TrueKillUtil;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Locks recovery while terminal damage is being finalized. Public health and
 * alive/dead queries remain untouched so clients only see a death after the
 * entity's real die/tickDeath transaction has started.
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class LivingEntityTerminalStateMixin implements TerminalDeathStateAccess {
    @Shadow
    protected boolean dead;

    @Override
    public boolean endsustain$deathTransactionStarted() {
        return this.dead;
    }

    @Unique
    private boolean endsustain$terminalState() {
        return ((LivingEntity) (Object) this).getPersistentData()
                .getBoolean(TrueKillUtil.TERMINAL_STATE_KEY);
    }

    @Inject(method = "setHealth", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockPositiveHealth(float health, CallbackInfo ci) {
        if (endsustain$terminalState() && health > 0.0F) ci.cancel();
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockHealing(float amount, CallbackInfo ci) {
        if (endsustain$terminalState()) ci.cancel();
    }

    @Inject(method = "setAbsorptionAmount", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockPositiveAbsorption(float amount, CallbackInfo ci) {
        if (endsustain$terminalState() && amount > 0.0F) ci.cancel();
    }
}
