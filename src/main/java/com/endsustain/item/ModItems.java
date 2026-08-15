package com.endsustain.item;

import com.endsustain.EndSustain;
import com.endsustain.entity.ModEntities;
import net.minecraft.world.item.Item;
import net.minecraftforge.common.ForgeSpawnEggItem;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {
    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, EndSustain.MOD_ID);

    // Boss 掉落：终焉核心
    public static final RegistryObject<Item> ENDSUSTAIN_CORE = ITEMS.register("endsustain_core",
            () -> new EndsustainCoreItem(new Item.Properties().stacksTo(16).fireResistant()));

    // 终焉之刃
    public static final RegistryObject<Item> ENDSUSTAIN_BLADE = ITEMS.register("endsustain_blade",
            () -> new com.endsustain.item.weapon.EndsustainBladeItem(
                    new Item.Properties().durability(2031).fireResistant()));

    // 开发者彩蛋：ilusi 可爱橙
    public static final RegistryObject<Item> ILUSI = ITEMS.register("ilusi",
            () -> new IlusiItem(new Item.Properties().stacksTo(16)
                    .food(new net.minecraft.world.food.FoodProperties.Builder()
                            .nutrition(3)
                            .saturationMod(0.67F)
                            .build())));

    public static final RegistryObject<Item> ILUSI_ATE = ITEMS.register("ilusi_ate",
            () -> new IlusiAteItem(new Item.Properties().stacksTo(1)));

    // 紧急登出装置：清除附近 Boss 实体
    public static final RegistryObject<Item> EMERGENCY_LOGOUT_DEVICE = ITEMS.register("emergency_logout_device",
            () -> new EmergencyLogoutDeviceItem(new Item.Properties().stacksTo(1)));

    // 制作人员彩蛋：哈基巫的祝（猪）福
    public static final RegistryObject<Item> HAKI_WITCH_PIG_BLESSING = ITEMS.register("voodom_blessing",
            () -> new HakiWitchPigBlessingItem(new Item.Properties().stacksTo(16)));

    // 对使用“末影蘸酱”YSM 模型的女仆使用剪刀获得
    public static final RegistryObject<Item> STOCKINGS = ITEMS.register("stockings",
            () -> new Item(new Item.Properties().stacksTo(64)));

    // 蘸酱玩偶
    public static final RegistryObject<Item> ZHAJIANG_DOLL = ITEMS.register("zhanjiangdoll",
            () -> new ZhajiangDollItem(new Item.Properties().stacksTo(64)));

    public static final RegistryObject<Item> SMALL_ZHANJIANG = ITEMS.register("small_zhanjiang",
            () -> new SmallZhanjiangItem(new Item.Properties().stacksTo(1).fireResistant()));

    // ---- 刷怪蛋 ----
    public static final RegistryObject<Item> FINALE_SPAWN_EGG = ITEMS.register("finale_spawn_egg",
            () -> new ForgeSpawnEggItem(ModEntities.FINALE, 0x161616, 0x000000,
                    new Item.Properties()));

    public static void register(IEventBus modBus) {
        ITEMS.register(modBus);
    }
}
