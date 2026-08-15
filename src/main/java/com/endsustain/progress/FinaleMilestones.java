package com.endsustain.progress;

import com.endsustain.EndSustain;
import net.minecraft.advancements.Advancement;
import net.minecraft.advancements.AdvancementProgress;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

public final class FinaleMilestones {
    public static final ResourceLocation RITUAL_STARTED = id("finale_ritual_started");
    public static final ResourceLocation BATTLE_STARTED = id("finale_battle_started");
    public static final ResourceLocation DEFEATED = id("finale_defeated");

    private FinaleMilestones() {}
    private static ResourceLocation id(String path) { return new ResourceLocation(EndSustain.MOD_ID, path); }

    public static void award(ServerPlayer player, ResourceLocation id) {
        Advancement advancement = player.server.getAdvancements().getAdvancement(id);
        if (advancement == null) return;
        AdvancementProgress progress = player.getAdvancements().getOrStartProgress(advancement);
        for (String criterion : progress.getRemainingCriteria()) player.getAdvancements().award(advancement, criterion);
    }

    public static boolean has(ServerPlayer player, ResourceLocation id) {
        Advancement advancement = player.server.getAdvancements().getAdvancement(id);
        return advancement != null && player.getAdvancements().getOrStartProgress(advancement).isDone();
    }
}
