package com.endsustain;

import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.network.EndSustainNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FinaleEnvironmentState {
    private static boolean active;

    private FinaleEnvironmentState() {}

    public static boolean isActive() {
        return active;
    }

    public static boolean isSpawnBoostActive() {
        return active && EndSustainConfig.ENABLE_FINALE_SPAWN_BOOST.get();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();
        boolean present = false;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getAllEntities()) {
                if (entity instanceof FinaleEndsustainEntity finale && finale.isAlive()
                        && !finale.isRemoved() && level.isLoaded(finale.blockPosition())) {
                    present = true;
                    break;
                }
            }
            if (present) break;
        }

        if (present != active) {
            active = present;
            EndSustainNetwork.broadcastEnvironment(server, active);
        } else if (active && server.getTickCount() % 40 == 0) {
            EndSustainNetwork.broadcastEnvironment(server, true);
        }

        if (active) {
            for (ServerLevel level : server.getAllLevels()) {
                level.setWeatherParameters(0, 24000, true, true);
                level.setRainLevel(1.0F);
                level.setThunderLevel(1.0F);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) EndSustainNetwork.sendEnvironment(player, active);
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) EndSustainNetwork.sendEnvironment(player, active);
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) EndSustainNetwork.sendEnvironment(player, active);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        active = false;
    }
}
