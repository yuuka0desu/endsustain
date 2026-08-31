package com.endsustain.client;

import com.endsustain.EndSustain;
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
    /** 滤镜兜底超时：服务器 end 包丢失或玩家中途脱离时自动关闭。 */
    private static final int FILTER_TIMEOUT_TICKS = 240;
    private static boolean active;
    private static volatile boolean globalEnvironmentActive;
    private static int filterTicksLeft;

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

    /** 终结技释放前约 5 秒：由服务器在魅惑提示（beginCharm）时经网络包触发。 */
    public static void startUltimateFilter() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!active && minecraft.gameRenderer != null) {
            minecraft.gameRenderer.loadEffect(FILTER);
            active = true;
        }
        filterTicksLeft = FILTER_TIMEOUT_TICKS;
    }

    /** 终结技命中后：由服务器在 applyTrueDamage 时广播结束。 */
    public static void stopUltimateFilter() {
        Minecraft minecraft = Minecraft.getInstance();
        if (active && minecraft.gameRenderer != null) {
            minecraft.gameRenderer.shutdownEffect();
            active = false;
        }
        filterTicksLeft = 0;
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
        // 滤镜兜底：客户端脱离世界或超时未收到结束包时自动关闭，避免黑白滤镜残留。
        if (active) {
            if (minecraft.level == null || minecraft.player == null || --filterTicksLeft <= 0) {
                stopUltimateFilter();
            }
        }
    }
}
