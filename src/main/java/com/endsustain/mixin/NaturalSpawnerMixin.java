package com.endsustain.mixin;

import com.endsustain.FinaleEnvironmentState;
import com.endsustain.config.EndSustainConfig;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(NaturalSpawner.SpawnState.class)
public abstract class NaturalSpawnerMixin {
    @Redirect(method = "canSpawnForCategory", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/entity/MobCategory;getMaxInstancesPerChunk()I"))
    private int endsustain$multiplyGlobalCap(MobCategory category) {
        int base = category.getMaxInstancesPerChunk();
        return FinaleEnvironmentState.isSpawnBoostActive()
                ? base * EndSustainConfig.FINALE_MOB_CAP_MULTIPLIER.get() : base;
    }
}
