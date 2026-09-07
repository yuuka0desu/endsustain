package com.endsustain.mixin;

import com.endsustain.combat.EndsustainTerminal;
import com.endsustain.combat.TerminalDeathStateAccess;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.common.ForgeHooks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 终焉标记实体的原版方法内拦截，优先级高于事件层防御：
 * - setHealth：正数写入被改写为 0，回血无入口。
 * - setAbsorptionAmount：正数吸收被改写为 0。
 * - heal：直接取消。
 * - ForgeHooks.onLivingDeath：对标记实体恒返回 false，任何 LivingDeathEvent 取消均无效。
 * 注意：不再清除 die 的预置死标志——die 由终焉结算链在死亡事务未建立时
 * 单次调用，重复执行死亡入口会导致演出型实体重复发放掉落。
 */
@Mixin(value = LivingEntity.class, priority = 2000)
public abstract class LivingEntityTerminalStateMixin implements TerminalDeathStateAccess {
    @Shadow
    protected boolean dead;

    @Override
    public boolean endsustain$deathTransactionStarted() {
        return this.dead;
    }

    @Override
    public void endsustain$forceDeathTransaction() {
        this.dead = true;
    }

    @Unique
    private boolean endsustain$terminalState() {
        return ((LivingEntity) (Object) this).getPersistentData()
                .getBoolean(EndsustainTerminal.TERMINAL_KEY);
    }

    @ModifyArg(method = "setHealth", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Mth;clamp(FFF)F"), index = 0)
    private float endsustain$forceZeroHealth(float health) {
        return endsustain$terminalState() ? 0.0F : health;
    }

    @Inject(method = "setAbsorptionAmount", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockPositiveAbsorption(float amount, CallbackInfo ci) {
        if (endsustain$terminalState() && amount > 0.0F) ci.cancel();
    }

    @Inject(method = "heal", at = @At("HEAD"), cancellable = true)
    private void endsustain$blockHealing(float amount, CallbackInfo ci) {
        if (endsustain$terminalState()) ci.cancel();
    }

    /**
     * getHealth 劫持：五层归零完成后（HEALTH_HIJACK_KEY），
     * 标记实体的 getHealth 恒返回 0——回滚/回血逻辑读到的生命恒为 0，
     * 死亡状态机必然接管，任何外部写回都被读取层屏蔽。
     */
    @Inject(method = "getHealth", at = @At("HEAD"), cancellable = true)
    private void endsustain$hijackGetHealth(
            org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable<Float> cir) {
        LivingEntity self = (LivingEntity) (Object) this;
        if (self.getPersistentData().getBoolean(EndsustainTerminal.TERMINAL_KEY)
                && self.getPersistentData().getBoolean(EndsustainTerminal.HEALTH_HIJACK_KEY)) {
            cir.setReturnValue(0.0F);
        }
    }

    @Redirect(method = "dropAllDeathLoot", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/common/ForgeHooks;onLivingDrops(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;Ljava/util/Collection;IZ)Z"))
    private boolean endsustain$forceLootDrops(LivingEntity entity, DamageSource source,
                                              java.util.Collection<net.minecraft.world.entity.item.ItemEntity> drops,
                                              int looting, boolean recentlyHit) {
        boolean canceled = ForgeHooks.onLivingDrops(entity, source, drops, looting, recentlyHit);
        if (endsustain$terminalState()) {
            // 标记实体无视掉落事件取消：掉落正常进入世界
            return false;
        }
        return canceled;
    }

    @Redirect(method = "die", at = @At(value = "INVOKE", remap = false,
            target = "Lnet/minecraftforge/common/ForgeHooks;onLivingDeath(Lnet/minecraft/world/entity/LivingEntity;Lnet/minecraft/world/damagesource/DamageSource;)Z"))
    private boolean endsustain$forceDeathEntry(LivingEntity entity, DamageSource source) {
        if (endsustain$terminalState()) {
            // 仍触发死亡事件链（掉落钩子、任务统计、分裂等后续事件正常发生），
            // 但无视返回值——任何取消死亡的尝试对标记实体无效。
            ForgeHooks.onLivingDeath(entity, source);
            return false;
        }
        return ForgeHooks.onLivingDeath(entity, source);
    }
}
