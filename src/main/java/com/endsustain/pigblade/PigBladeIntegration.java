package com.endsustain.pigblade;

import com.endsustain.pigblade.registry.PigBladeRegistry;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 末影猪咪 蘸酱 —— 整合入口：注册 SE / SA / ComboState / 实体。
 */
public final class PigBladeIntegration {
    private PigBladeIntegration() {
    }

    public static void register(IEventBus modBus) {
        PigBladeRegistry.register(modBus);
    }
}
