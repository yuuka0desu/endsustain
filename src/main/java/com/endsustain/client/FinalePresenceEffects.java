package com.endsustain.client;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FinalePresenceEffects {
    private static final ResourceLocation FILTER =
            new ResourceLocation(EndSustain.MOD_ID, "shaders/post/finale_presence.json");
    private static boolean active;
    private static volatile boolean globalEnvironmentActive;

    private FinalePresenceEffects() {}

    public static void setGlobalEnvironmentActive(boolean value) {
        globalEnvironmentActive = value;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) {
            minecraft.level.setRainLevel(value ? 1.0F : 0.0F);
            minecraft.level.setThunderLevel(value ? 1.0F : 0.0F);
            minecraft.gameRenderer.lightTexture().tick();
        }
    }

    public static boolean isGlobalEnvironmentActive() {
        return globalEnvironmentActive;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        // 仅由服务器状态包控制，切换维度期间 player/level 的短暂空值不清除全局天气。
        if (globalEnvironmentActive && minecraft.level != null) {
            minecraft.level.setRainLevel(1.0F);
            minecraft.level.setThunderLevel(1.0F);
        }
        boolean present = minecraft.level != null && minecraft.player != null
                && !minecraft.level.getEntitiesOfClass(FinaleEndsustainEntity.class,
                minecraft.player.getBoundingBox().inflate(256.0D), FinaleEndsustainEntity::isAlive).isEmpty();

        if (present && !active) {
            minecraft.gameRenderer.loadEffect(FILTER);
            active = true;
        } else if (!present && active) {
            minecraft.gameRenderer.shutdownEffect();
            active = false;
        }
    }
}
