package com.endsustain;

import com.endsustain.compat.CompatHandler;
import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.ModEntities;
import com.endsustain.effect.ModEffects;
import com.endsustain.item.ModItems;
import com.endsustain.network.EndSustainNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

@Mod(EndSustain.MOD_ID)
public class EndSustain {
    public static final String MOD_ID = "endsustain";
    public static final Logger LOGGER = LogUtils.getLogger();

    public EndSustain() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, EndSustainConfig.COMMON_SPEC);
        EndSustainNetwork.register();

        // 注册器（仅 deferred register，不触碰任何外部 API）
        ModItems.register(modBus);
        ModEntities.register(modBus);
        ModEffects.register(modBus);
        com.endsustain.registry.ModCreativeTabs.register(modBus);

        // compat 延迟到 FMLCommonSetupEvent 初始化，见 ModBusEvents
        LOGGER.info("[终焉维系] EndSustain 初始化完成");
    }
}
