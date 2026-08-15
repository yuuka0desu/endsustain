package com.endsustain.mixin;

import com.endsustain.client.FinalePresenceEffects;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public abstract class LightTextureMixin {
    @Shadow @Final private DynamicTexture lightTexture;
    @Shadow @Final private NativeImage lightPixels;

    @Inject(method = "updateLightTexture", at = @At("TAIL"))
    private void endsustain$zeroSkyLight(float partialTick, CallbackInfo ci) {
        if (!FinalePresenceEffects.isGlobalEnvironmentActive()) return;
        for (int block = 0; block < 16; block++) {
            int zeroSkyColor = lightPixels.getPixelRGBA(block, 0);
            for (int sky = 1; sky < 16; sky++) lightPixels.setPixelRGBA(block, sky, zeroSkyColor);
        }
        lightTexture.upload();
    }
}
