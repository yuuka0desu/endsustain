package com.endsustain.client;

import com.endsustain.EndSustain;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FinaleWeatherEvents {
    private FinaleWeatherEvents() {}

    @SubscribeEvent
    public static void renderIndependentPurpleRain(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        if (!FinalePresenceEffects.isGlobalEnvironmentActive()) return;
        FinaleWeatherRenderer.render(event.getPoseStack(), event.getPartialTick(), event.getCamera());
    }
}
