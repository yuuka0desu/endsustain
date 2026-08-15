package com.endsustain.client;

import com.endsustain.client.screen.FinalePathScreen;
import net.minecraft.client.Minecraft;

public final class FinalePathClientState {
    public static int storyMask, witnessMask, tier;
    private FinalePathClientState() {}

    public static void openPending() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && mc.level != null && !(mc.screen instanceof FinalePathScreen)) {
            mc.setScreen(new FinalePathScreen());
        }
    }

    public static void receive(boolean allowed, int story, int witness, int skillTier) {
        Minecraft mc = Minecraft.getInstance();
        if (!allowed) {
            if (mc.screen instanceof FinalePathScreen) mc.setScreen(null);
            if (mc.player != null) mc.player.displayClientMessage(
                    net.minecraft.network.chat.Component.translatable("message.endsustain.finale_path_requires_guide"), true);
            return;
        }
        storyMask = story; witnessMask = witness; tier = skillTier;
        if (mc.screen instanceof FinalePathScreen screen) screen.refresh();
        else mc.setScreen(new FinalePathScreen());
    }
}
