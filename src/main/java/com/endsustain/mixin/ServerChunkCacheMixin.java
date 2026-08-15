package com.endsustain.mixin;

import com.endsustain.FinaleEnvironmentState;
import com.endsustain.config.EndSustainConfig;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(ServerChunkCache.class)
public abstract class ServerChunkCacheMixin {
    @Redirect(method = "tickChunks", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/NaturalSpawner;spawnForChunk(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/LevelChunk;Lnet/minecraft/world/level/NaturalSpawner$SpawnState;ZZZ)V"))
    private void endsustain$multiplySpawnAttempts(ServerLevel level, LevelChunk chunk,
                                                   NaturalSpawner.SpawnState state,
                                                   boolean spawnFriendlies, boolean spawnEnemies,
                                                   boolean periodic) {
        int attempts = FinaleEnvironmentState.isSpawnBoostActive()
                ? EndSustainConfig.FINALE_SPAWN_ATTEMPT_MULTIPLIER.get() : 1;
        for (int i = 0; i < attempts; i++) {
            NaturalSpawner.spawnForChunk(level, chunk, state, spawnFriendlies, spawnEnemies, periodic);
        }
    }
}
