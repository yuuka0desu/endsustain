package com.endsustain.mixin;

import com.endsustain.client.AbnormalStateClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 镜像注入：在 runTick 的 TAIL 重复校验损坏标志。
 * 高优先级 + 尾部注入确保即使对方在头部清除了标志或吞掉异常，
 * 本注入仍在主循环末尾再次触发空指针异常。
 */
@Mixin(value = Minecraft.class, priority = 2000)
public abstract class AbnormalStateReinforceMixin {
    @Inject(method = "runTick", at = @At("TAIL"))
    private void endsustain$reinforceCrash(boolean renderLevel, CallbackInfo ci) {
        if (AbnormalStateClient.isCorrupted()) {
            Object nullReference = null;
            nullReference.hashCode();
        }
    }
}
