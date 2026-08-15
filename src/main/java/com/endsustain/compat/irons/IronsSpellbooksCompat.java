package com.endsustain.compat.irons;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Iron's Spellbooks 兼容子模块 —— 通过反射调用真实法术。
 * 仅在 irons_spellbooks 已加载时被 CompatHandler 调用。
 */
public class IronsSpellbooksCompat {

    private static Class<?> spellRegistryClass;
    private static Class<?> abstractSpellClass;
    private static Class<?> castSourceClass;
    private static Class<?> magicDataClass;
    private static Object castSourceMob;
    private static Method onCastMethod;

    // 法术名 → SpellRegistry 字段名
    private static final java.util.Map<String, String> SPELL_MAP = java.util.Map.of(
            "black_hole",       "BLACK_HOLE_SPELL",
            "magic_missile",    "MAGIC_MISSILE_SPELL",
            "starfall",         "STARFALL_SPELL",
            "chaos_orb",        "SCORCH_SPELL",       // 无 chaos_orb，用 scorch 替代
            "devour",           "DEVOUR_SPELL",
            "ray_of_siphoning", "RAY_OF_SIPHONING_SPELL"
    );

    private static final java.util.Map<String, Integer> SPELL_LEVELS = java.util.Map.of(
            "black_hole",       10,
            "magic_missile",    255,
            "starfall",         255,
            "chaos_orb",        35,
            "devour",           35,
            "ray_of_siphoning", 35
    );

    public static void init(IEventBus modBus) {
        try {
            // 定位核心类
            spellRegistryClass = Class.forName("io.redspace.ironsspellbooks.api.registry.SpellRegistry");
            abstractSpellClass = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
            castSourceClass    = Class.forName("io.redspace.ironsspellbooks.api.spells.CastSource");
            magicDataClass     = Class.forName("io.redspace.ironsspellbooks.api.magic.MagicData");

            // 获取 CastSource.MOB 枚举值
            for (Object cs : castSourceClass.getEnumConstants()) {
                if (cs.toString().equals("MOB")) { castSourceMob = cs; break; }
            }

            // onCast(Level, int, LivingEntity, CastSource, MagicData)
            onCastMethod = abstractSpellClass.getMethod("onCast",
                    net.minecraft.world.level.Level.class, int.class,
                    LivingEntity.class, castSourceClass, magicDataClass);

            EndSustain.LOGGER.info("[终焉维系] Iron's Spellbooks API 绑定成功");
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[终焉维系] Iron's Spellbooks API 绑定失败: {}", t.toString());
        }
    }

    /** 由 FinaleEndsustainEntity.castIronSpell 调用 */
    public static boolean castSpell(FinaleEndsustainEntity boss, Player target, String spellId) {
        if (spellRegistryClass == null || onCastMethod == null) return false;
        try {
            String fieldName = SPELL_MAP.get(spellId);
            if (fieldName == null) return false;
            Field field = spellRegistryClass.getField(fieldName);
            Object regObj = field.get(null); // RegistryObject<AbstractSpell>
            Object spell = regObj.getClass().getMethod("get").invoke(regObj);

            int level = SPELL_LEVELS.getOrDefault(spellId, 1);
            Object magicData = magicDataClass.getDeclaredConstructor().newInstance();

            onCastMethod.invoke(spell, boss.level(), level, boss, castSourceMob, magicData);

            EndSustain.LOGGER.debug("[终焉维系] 铁魔法施放: {} lv{}", spellId, level);
            return true;
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[终焉维系] 铁魔法施放失败 {}: {}", spellId, t.toString());
            return false;
        }
    }
}
