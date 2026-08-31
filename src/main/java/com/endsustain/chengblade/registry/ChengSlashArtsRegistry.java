package com.endsustain.chengblade.registry;

import com.endsustain.chengblade.ChengBladeIntegration;
import com.endsustain.chengblade.slasharts.MiaoqieSlashArts;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ChengSlashArtsRegistry {
    public static final DeferredRegister<SlashArts> SLASH_ARTS =
            DeferredRegister.create(SlashArts.REGISTRY_KEY, ChengBladeIntegration.MOD_ID);

    public static final RegistryObject<SlashArts> MIAOQIE = SLASH_ARTS.register(
            "miaoqie", ChengSlashArtsRegistry::createMiaoqie);

    private static SlashArts createMiaoqie() {
        SlashArts arts = new SlashArts(entity ->
                new ResourceLocation(ChengBladeIntegration.MOD_ID, "miaoqie"));
        arts.setProudSoulCost(MiaoqieSlashArts.PROUD_SOUL_COST);
        return arts;
    }

    private ChengSlashArtsRegistry() {
    }
}
