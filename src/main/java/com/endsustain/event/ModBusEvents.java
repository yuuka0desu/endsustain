package com.endsustain.event;

import com.endsustain.EndSustain;
import com.endsustain.compat.goety.ClosingRitualCompat;
import com.endsustain.entity.ModEntities;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.entity.boss.QunUEntity;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import top.theillusivec4.curios.api.CuriosApi;
import com.endsustain.item.ModItems;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;

/**
 * MOD 事件总线监听：注册实体属性等。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
public class ModBusEvents {

    @SubscribeEvent
    public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        event.put(ModEntities.FINALE.get(), FinaleEndsustainEntity.createAttributes().build());
        event.put(ModEntities.QUN_U.get(), QunUEntity.createAttributes().build());
        event.put(ModEntities.SMALL_ZHANJIANG_COMPANION.get(), SmallZhanjiangCompanionEntity.createAttributes().build());
    }

    @SubscribeEvent
    public static void onCommonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ClosingRitualCompat.register();
            CuriosApi.registerCurio(ModItems.SMALL_ZHANJIANG.get(),
                    (top.theillusivec4.curios.api.type.capability.ICurioItem) ModItems.SMALL_ZHANJIANG.get());
        });
        EndSustain.LOGGER.info("[落幕终焉] 通用初始化阶段完成");
    }
}
