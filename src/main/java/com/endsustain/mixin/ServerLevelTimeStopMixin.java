package com.endsustain.mixin;

import com.endsustain.combat.TimeStopManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerLevel.class)
public abstract class ServerLevelTimeStopMixin {
    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void endsustain$freezeEntityTick(Entity entity, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(entity)) {
            entity.setDeltaMovement(0.0D, 0.0D, 0.0D);
            entity.hurtMarked = true;
            ci.cancel();
        }
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void endsustain$freezePassengerTick(Entity vehicle, Entity passenger, CallbackInfo ci) {
        if (TimeStopManager.isFrozen(passenger)) ci.cancel();
    }

    @Inject(method = "tickChunk", at = @At("HEAD"), cancellable = true)
    private void endsustain$freezeChunkTick(LevelChunk chunk, int randomTickSpeed, CallbackInfo ci) {
        ServerLevel level = (ServerLevel) (Object) this;
        if (TimeStopManager.isFrozenPosition(level, chunk.getPos().getWorldPosition())) ci.cancel();
    }
}
