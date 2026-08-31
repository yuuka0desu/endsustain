package com.endsustain.pigblade.registry;

import com.endsustain.EndSustain;
import com.endsustain.pigblade.PigFormPig;
import com.endsustain.pigblade.slasharts.PigPierceHeart;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.registry.combo.ComboState;
import mods.flammpfeil.slashblade.registry.specialeffects.SpecialEffect;
import mods.flammpfeil.slashblade.slasharts.SlashArts;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 末影猪咪 蘸酱 —— 拔刀剑附属内容注册表。
 * 命名空间统一使用 endsustain，运行时依赖 SlashBlade Resharped。
 */
public final class PigBladeRegistry {
    public static final DeferredRegister<SpecialEffect> SPECIAL_EFFECTS =
            DeferredRegister.create(SpecialEffect.REGISTRY_KEY, EndSustain.MOD_ID);
    public static final DeferredRegister<SlashArts> SLASH_ARTS =
            DeferredRegister.create(SlashArts.REGISTRY_KEY, EndSustain.MOD_ID);
    public static final DeferredRegister<ComboState> COMBO_STATES =
            DeferredRegister.create(ComboState.REGISTRY_KEY, EndSustain.MOD_ID);
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES, EndSustain.MOD_ID);

    // ======================== SE ========================

    /** 你已猪咪：挥刀发射幻影剑，命中敌人将其短暂替换为猪。 */
    public static final RegistryObject<SpecialEffect> YOU_ARE_PIG = SPECIAL_EFFECTS.register(
            "you_are_pig", () -> new SpecialEffect(30, false, false) {});

    /** 猪咪之力：手持该剑时所有幻影剑模型改为猪，命中播放猪受伤音效。 */
    public static final RegistryObject<SpecialEffect> PIG_FORCE = SPECIAL_EFFECTS.register(
            "pig_force", () -> new SpecialEffect(30, false, false) {});

    // ======================== SA ========================

    /** 万猪穿心：蓄力生成 10 道幻影剑从不同角度穿刺目标。 */
    public static final RegistryObject<SlashArts> PIG_PIERCE_HEART = SLASH_ARTS.register(
            "pig_pierce_heart", () -> {
                SlashArts arts = new SlashArts(entity ->
                        new ResourceLocation(EndSustain.MOD_ID, "pig_pierce_heart"));
                arts.setProudSoulCost(PigPierceHeart.PROUD_SOUL_COST);
                return arts;
            });

    /** 万猪穿心蓄力 ComboState：按住蓄力，松开后进入本 combo 自动发射 10 道幻影剑。 */
    public static final RegistryObject<ComboState> PIG_PIERCE_COMBO = COMBO_STATES.register(
            "pig_pierce_heart", () -> ComboState.Builder.newInstance()
                    .startAndEnd(0, 20)
                    .priority(90)
                    .speed(0.4F)
                    .motionLoc(SlashBlade.prefix("combostate/motion"))
                    .nextOfTimeout(entity -> SlashBlade.prefix("none"))
                    .addTickAction(entity -> {
                        // 进入 combo 后立即自动释放（仅服务端），无需玩家再次点击；
                        // 宽窗口防止首帧 elapsed 错位导致漏触发
                        if (ComboState.getElapsed(entity) <= 5L) {
                            PigPierceHeart.release(entity);
                        }
                    })
                    .build());

    // ======================== 实体 ========================

    /** 猪形态替身：继承原实体血量，5 秒后强制还原。 */
    public static final RegistryObject<EntityType<PigFormPig>> PIG_FORM =
            ENTITIES.register("pig_form", () -> EntityType.Builder
                    .<PigFormPig>of(PigFormPig::new, MobCategory.CREATURE)
                    .sized(0.9F, 0.9F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("pig_form"));

    private PigBladeRegistry() {
    }

    public static void register(IEventBus modBus) {
        SPECIAL_EFFECTS.register(modBus);
        SLASH_ARTS.register(modBus);
        COMBO_STATES.register(modBus);
        ENTITIES.register(modBus);
    }
}
