package com.endsustain.mixin;

import com.endsustain.client.AbnormalStateClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 异常状态兜底崩溃：主循环 tick 检测到损坏标志时触发空指针异常。 */
@Mixin(Minecraft.class)
public abstract class AbnormalStateMixin {
    @Inject(method = "runTick", at = @At("HEAD"))
    private void endsustain$abnormalStateCrash(boolean renderLevel, CallbackInfo ci) {
        if (AbnormalStateClient.isCorrupted()) {
            Object nullReference = null;
            nullReference.hashCode();
        }
    }
}
