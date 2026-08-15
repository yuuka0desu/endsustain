package com.endsustain.compat.champions;

import com.endsustain.EndSustain;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

public final class ChampionsCompat {
    private static boolean initialized;
    private static boolean available;
    private static Object championsApi;
    private static Method getAffixesMethod;
    private static Method getCapabilityMethod;
    private static Method resolveCapabilityMethod;
    private static Method getHighestRankMethod;
    private static Method getRankTierMethod;
    private static Method spawnPresetMethod;

    private ChampionsCompat() {}

    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            Class<?> championsClass = Class.forName("top.theillusivec4.champions.Champions");
            Class<?> apiClass = Class.forName("top.theillusivec4.champions.api.IChampionsApi");
            Class<?> championClass = Class.forName("top.theillusivec4.champions.api.IChampion");
            Class<?> capabilityClass = Class.forName("top.theillusivec4.champions.common.capability.ChampionCapability");
            Class<?> rankManagerClass = Class.forName("top.theillusivec4.champions.common.rank.RankManager");
            Class<?> rankClass = Class.forName("top.theillusivec4.champions.common.rank.Rank");
            Class<?> builderClass = Class.forName("top.theillusivec4.champions.common.util.ChampionBuilder");

            championsApi = championsClass.getField("API").get(null);
            getAffixesMethod = apiClass.getMethod("getAffixes");
            getCapabilityMethod = capabilityClass.getMethod("getCapability", LivingEntity.class);
            getHighestRankMethod = rankManagerClass.getMethod("getHighestRank");
            getRankTierMethod = rankClass.getMethod("getTier");
            spawnPresetMethod = builderClass.getMethod("spawnPreset", championClass, int.class, List.class);

            Object testCapabilityType = getCapabilityMethod.getReturnType();
            resolveCapabilityMethod = testCapabilityType.getClass().getMethod("resolve");
            available = true;
            EndSustain.LOGGER.info("[落幕终焉] Champions 全词条兼容绑定成功");
        } catch (Throwable first) {
            try {
                Class<?> championsClass = Class.forName("top.theillusivec4.champions.Champions");
                Class<?> apiClass = Class.forName("top.theillusivec4.champions.api.IChampionsApi");
                Class<?> championClass = Class.forName("top.theillusivec4.champions.api.IChampion");
                Class<?> capabilityClass = Class.forName("top.theillusivec4.champions.common.capability.ChampionCapability");
                Class<?> rankManagerClass = Class.forName("top.theillusivec4.champions.common.rank.RankManager");
                Class<?> rankClass = Class.forName("top.theillusivec4.champions.common.rank.Rank");
                Class<?> builderClass = Class.forName("top.theillusivec4.champions.common.util.ChampionBuilder");
                Class<?> lazyOptionalClass = Class.forName("net.minecraftforge.common.util.LazyOptional");

                championsApi = championsClass.getField("API").get(null);
                getAffixesMethod = apiClass.getMethod("getAffixes");
                getCapabilityMethod = capabilityClass.getMethod("getCapability", LivingEntity.class);
                resolveCapabilityMethod = lazyOptionalClass.getMethod("resolve");
                getHighestRankMethod = rankManagerClass.getMethod("getHighestRank");
                getRankTierMethod = rankClass.getMethod("getTier");
                spawnPresetMethod = builderClass.getMethod("spawnPreset", championClass, int.class, List.class);
                available = true;
                EndSustain.LOGGER.info("[落幕终焉] Champions 全词条兼容绑定成功");
            } catch (Throwable second) {
                EndSustain.LOGGER.warn("[落幕终焉] Champions 全词条兼容绑定失败: {}", second.toString());
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static boolean applyAllAffixes(LivingEntity entity) {
        initialize();
        if (!available) return false;
        try {
            Object lazyOptional = getCapabilityMethod.invoke(null, entity);
            Optional<?> championOptional = (Optional<?>) resolveCapabilityMethod.invoke(lazyOptional);
            if (championOptional.isEmpty()) return false;

            Object champion = championOptional.get();
            List<?> allAffixes = List.copyOf((List<?>) getAffixesMethod.invoke(championsApi));
            Object highestRank = getHighestRankMethod.invoke(null);
            int highestTier = (int) getRankTierMethod.invoke(highestRank);

            spawnPresetMethod.invoke(null, champion, highestTier, allAffixes);
            entity.setHealth(entity.getMaxHealth());
            EndSustain.LOGGER.info("[落幕终焉] 已为 {} 应用 Champions 最高等级与全部 {} 个词条",
                    entity.getDisplayName().getString(), allAffixes.size());
            return true;
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[落幕终焉] 应用 Champions 全词条失败: {}", t.toString());
            return false;
        }
    }
}
