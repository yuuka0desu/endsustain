package com.endsustain.client;

import com.endsustain.EndSustain;
import com.endsustain.network.EndSustainNetwork;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

public final class FinalePathKeyHandler {
    public static final KeyMapping OPEN = key("key.endsustain.finale_path", GLFW.GLFW_KEY_Z);
    public static final KeyMapping TIDAL_TENTACLE_TOGGLE = key("key.endsustain.skill.tidal_toggle", GLFW.GLFW_KEY_G);
    public static final KeyMapping[] SKILLS = {
            key("key.endsustain.skill.harbinger", GLFW.GLFW_KEY_X),
            key("key.endsustain.skill.abyss", GLFW.GLFW_KEY_C),
            key("key.endsustain.skill.ember", GLFW.GLFW_KEY_V),
            key("key.endsustain.skill.soulfire", GLFW.GLFW_KEY_B),
            key("key.endsustain.skill.void_crown", GLFW.GLFW_KEY_N),
            key("key.endsustain.skill.apocalypse", GLFW.GLFW_KEY_M)
    };

    private static KeyMapping key(String name, int code) {
        return new KeyMapping(name, InputConstants.Type.KEYSYM, code, "key.categories.endsustain");
    }

    @Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class ModBus {
        @SubscribeEvent public static void register(RegisterKeyMappingsEvent event) {
            event.register(OPEN);
            event.register(TIDAL_TENTACLE_TOGGLE);
            for (KeyMapping skill : SKILLS) event.register(skill);
        }
    }
    @Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
    public static final class ForgeBus {
        @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            while (OPEN.consumeClick()) {
                FinalePathClientState.openPending();
                EndSustainNetwork.requestFinalePath();
            }
            while (TIDAL_TENTACLE_TOGGLE.consumeClick()) EndSustainNetwork.toggleTidalTentacles();
            for (int i = 0; i < SKILLS.length; i++) while (SKILLS[i].consumeClick()) EndSustainNetwork.triggerFinaleSkill(i);
        }
    }
}
