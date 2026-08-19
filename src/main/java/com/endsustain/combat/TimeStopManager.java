package com.endsustain.combat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/** Server-side regional stop used by the official finale execution sequence. */
public final class TimeStopManager {
    private static final Map<ResourceKey<Level>, Stop> ACTIVE = new HashMap<>();

    private TimeStopManager() {}

    public static void begin(ServerLevel level, Entity caster, int durationTicks) {
        int radiusChunks = Math.max(2, level.getServer().getPlayerList().getViewDistance());
        ACTIVE.put(level.dimension(), new Stop(caster.getUUID(), caster.blockPosition(),
                level.getGameTime() + durationTicks, radiusChunks));
    }

    public static void end(ServerLevel level) {
        ACTIVE.remove(level.dimension());
    }

    public static boolean isFrozenPosition(Level level, BlockPos position) {
        Stop stop = ACTIVE.get(level.dimension());
        if (stop == null) return false;
        if (level instanceof ServerLevel server && server.getGameTime() > stop.until) {
            ACTIVE.remove(server.dimension());
            return false;
        }
        return Math.abs(SectionPos.blockToSectionCoord(position.getX())
                        - SectionPos.blockToSectionCoord(stop.center.getX())) <= stop.radiusChunks
                && Math.abs(SectionPos.blockToSectionCoord(position.getZ())
                        - SectionPos.blockToSectionCoord(stop.center.getZ())) <= stop.radiusChunks;
    }

    public static boolean isFrozen(Entity entity) {
        Stop stop = ACTIVE.get(entity.level().dimension());
        if (stop == null || entity.getUUID().equals(stop.caster)) return false;
        if (entity.level() instanceof ServerLevel level && level.getGameTime() > stop.until) {
            ACTIVE.remove(level.dimension());
            return false;
        }
        int chunkRadius = stop.radiusChunks;
        return Math.abs(entity.chunkPosition().x - SectionPos.blockToSectionCoord(stop.center.getX())) <= chunkRadius
                && Math.abs(entity.chunkPosition().z - SectionPos.blockToSectionCoord(stop.center.getZ())) <= chunkRadius;
    }

    public static void tick(MinecraftServer server) {
        Iterator<Map.Entry<ResourceKey<Level>, Stop>> iterator = ACTIVE.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<ResourceKey<Level>, Stop> entry = iterator.next();
            Stop stop = entry.getValue();
            ServerLevel level = server.getLevel(entry.getKey());
            if (level == null || level.getGameTime() > stop.until) {
                iterator.remove();
                continue;
            }
            for (ServerPlayer player : level.players()) {
                if (!isFrozen(player)) continue;
                player.setDeltaMovement(Vec3.ZERO);
                player.hurtMarked = true;
                if (player.connection != null) {
                    player.connection.teleport(player.getX(), player.getY(), player.getZ(),
                            player.getYRot(), player.getXRot());
                }
            }
        }
    }

    private record Stop(UUID caster, BlockPos center, long until, int radiusChunks) {}
}
