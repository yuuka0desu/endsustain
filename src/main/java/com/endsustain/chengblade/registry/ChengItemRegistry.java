package com.endsustain.chengblade.registry;

import com.endsustain.chengblade.ChengBladeIntegration;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public final class ChengItemRegistry {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ChengBladeIntegration.MOD_ID);

    public static final RegistryObject<Item> BOTTLED_CHENG = ITEMS.register(
            "bottled_cheng", () -> new Item(new Item.Properties()));

    private ChengItemRegistry() {
    }
}
