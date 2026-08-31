package com.endsustain.chengblade;

import com.endsustain.chengblade.registry.ChengComboStateRegistry;
import com.endsustain.chengblade.registry.ChengEffectRegistry;
import com.endsustain.chengblade.registry.ChengItemRegistry;
import com.endsustain.chengblade.registry.ChengSlashArtsRegistry;
import com.endsustain.chengblade.registry.ChengSpecialEffectsRegistry;
import net.minecraftforge.eventbus.api.IEventBus;

/**
 * 橙「喵切」SlashBlade 附属内容，已并入 EndSustain。
 * 资源与注册表保持 cheng 命名空间，兼容原 ChengBlade 的存档与数据包。
 */
public final class ChengBladeIntegration {
    public static final String MOD_ID = "cheng";

    private ChengBladeIntegration() {
    }

    public static void register(IEventBus modBus) {
        ChengComboStateRegistry.COMBO_STATES.register(modBus);
        ChengSlashArtsRegistry.SLASH_ARTS.register(modBus);
        ChengSpecialEffectsRegistry.SPECIAL_EFFECTS.register(modBus);
        ChengItemRegistry.ITEMS.register(modBus);
        ChengEffectRegistry.EFFECTS.register(modBus);
    }
}
