package com.endsustain.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.github.L_Ender.cataclysm.entity.projectile.Tidal_Hook_Entity", remap = false)
public abstract class TidalHookEntityMixin extends AbstractArrow {
    @Shadow(remap = false) private ItemStack stack;
    @Shadow(remap = false) private boolean isPulling;
    @Shadow(remap = false) public abstract void m_6074_();

    protected TidalHookEntityMixin(EntityType<? extends AbstractArrow> type, Level level) {
        super(type, level);
    }

    @Redirect(method = "m_8119_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;m_21205_()Lnet/minecraft/world/item/ItemStack;", remap = false), remap = false)
    private ItemStack endsustain$keepVirtualClawsInMainHand(Player player) {
        return getPersistentData().getBoolean("EndsustainFinaleTidalHook") ? this.stack : player.getMainHandItem();
    }

    @Redirect(method = "m_8119_", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/player/Player;m_21206_()Lnet/minecraft/world/item/ItemStack;", remap = false), remap = false)
    private ItemStack endsustain$keepVirtualClawsInOffhand(Player player) {
        return getPersistentData().getBoolean("EndsustainFinaleTidalHook") ? this.stack : player.getOffhandItem();
    }

    @Inject(method = "m_8119_", at = @At("TAIL"), remap = false)
    private void endsustain$releaseAtDestination(CallbackInfo callback) {
        if (level().isClientSide || !getPersistentData().getBoolean("EndsustainFinaleTidalHook")) return;
        Entity owner = getOwner();
        if (!(owner instanceof Player player)) return;
        boolean arrived = this.isPulling && this.tickCount > 2 && player.distanceToSqr(this) <= 6.25D;
        boolean timedOut = this.tickCount >= 200;
        if (arrived || timedOut) {
            player.setNoGravity(false);
            player.setDeltaMovement(player.getDeltaMovement().multiply(0.2D, 0.2D, 0.2D));
            m_6074_();
        }
    }
}
