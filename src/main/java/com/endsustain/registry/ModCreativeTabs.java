package com.endsustain.registry;

import com.endsustain.EndSustain;
import com.endsustain.item.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.Registries;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, EndSustain.MOD_ID);

    public static final RegistryObject<CreativeModeTab> ENDSUSTAIN_TAB = TABS.register("endsustain_tab",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.endsustain"))
                    .icon(() -> new ItemStack(ModItems.ENDSUSTAIN_BLADE.get()))
                    .displayItems((params, output) -> {
                        output.accept(ModItems.ENDSUSTAIN_CORE.get());
                        output.accept(ModItems.ENDSUSTAIN_BLADE.get());
                        output.accept(ModItems.ILUSI.get());
                        output.accept(ModItems.ILUSI_ATE.get());
                        output.accept(ModItems.EMERGENCY_LOGOUT_DEVICE.get());
                        output.accept(ModItems.HAKI_WITCH_PIG_BLESSING.get());
                        output.accept(ModItems.STOCKINGS.get());
                        output.accept(ModItems.ZHAJIANG_DOLL.get());
                        output.accept(ModItems.SMALL_ZHANJIANG.get());
                        output.accept(ModItems.FINALE_SPAWN_EGG.get());
                    })
                    .build());

    public static void register(IEventBus modBus) {
        TABS.register(modBus);
    }
}
