package com.endsustain.compat;

import com.endsustain.EndSustain;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

/**
 * 适配兼容体系统一入口。
 * 通过 ModList.isLoaded(modId) 守门，按前置存在性初始化各兼容子模块，
 * 任一前置缺失时不会导致崩溃。
 */
public class CompatHandler {
    public static final String IRONS_SPELLBOOKS = "irons_spellbooks";
    public static final String YES_STEVE_MODEL = "yes_steve_model";
    public static final String GOETY = "goety";

    public static void init(IEventBus modBus) {
        if (ModList.get().isLoaded(IRONS_SPELLBOOKS)) {
            EndSustain.LOGGER.info("[终焉维系] 检测到 Iron's Spellbooks，启用法术兼容");
            com.endsustain.compat.irons.IronsSpellbooksCompat.init(modBus);
        }
        if (ModList.get().isLoaded(YES_STEVE_MODEL)) {
            EndSustain.LOGGER.info("[终焉维系] 检测到 Yes Steve Model，启用模型兼容");
            com.endsustain.compat.ysm.YsmCompat.init(modBus);
        }
        if (ModList.get().isLoaded(GOETY)) {
            EndSustain.LOGGER.info("[终焉维系] 检测到 Goety，启用魔法/亡灵兼容");
            com.endsustain.compat.goety.GoetyCompat.init(modBus);
        }
    }
}
