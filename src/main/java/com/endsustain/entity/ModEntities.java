package com.endsustain.entity;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.EndsustainBladeEntity;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.entity.boss.SpellProjectile;
import com.endsustain.entity.boss.QunUEntity;
import com.endsustain.entity.projectile.IlusiAteProjectileEntity;
import com.endsustain.entity.companion.SmallZhanjiangCompanionEntity;
import com.endsustain.entity.projectile.IlusiHomingTridentEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, EndSustain.MOD_ID);


    // Boss：落幕之终焉——末影蘸酱
    public static final RegistryObject<EntityType<FinaleEndsustainEntity>> FINALE =
            ENTITIES.register("finale", () -> EntityType.Builder.of(FinaleEndsustainEntity::new, MobCategory.MONSTER)
                    .sized(0.8F, 1.8F)
                    .fireImmune()
                    .build("finale"));

    public static final RegistryObject<EntityType<QunUEntity>> QUN_U =
            ENTITIES.register("qun_u", () -> EntityType.Builder.of(QunUEntity::new, MobCategory.MONSTER)
                    .sized(0.9F, 0.9F).clientTrackingRange(64).updateInterval(1).build("qun_u"));

    public static final RegistryObject<EntityType<SmallZhanjiangCompanionEntity>> SMALL_ZHANJIANG_COMPANION =
            ENTITIES.register("small_zhanjiang_companion", () -> EntityType.Builder
                    .<SmallZhanjiangCompanionEntity>of(SmallZhanjiangCompanionEntity::new, MobCategory.MISC)
                    .sized(0.2F, 0.45F).noSave().noSummon().fireImmune()
                    .clientTrackingRange(64).updateInterval(1).build("small_zhanjiang_companion"));

    // 终焉之刃投射物
    public static final RegistryObject<EntityType<EndsustainBladeEntity>> ENDSUSTAIN_BLADE_ENTITY =
            ENTITIES.register("endsustain_blade_projectile", () -> EntityType.Builder
                    .<EndsustainBladeEntity>of(EndsustainBladeEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("endsustain_blade_projectile"));

    // ilusi ¼橙投掷物
    public static final RegistryObject<EntityType<IlusiAteProjectileEntity>> ILUSI_ATE_PROJECTILE =
            ENTITIES.register("ilusi_ate_projectile", () -> EntityType.Builder
                    .<IlusiAteProjectileEntity>of(IlusiAteProjectileEntity::new, MobCategory.MISC)
                    .sized(0.35F, 0.35F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("ilusi_ate_projectile"));

    // ilusi 追踪三叉戟
    public static final RegistryObject<EntityType<IlusiHomingTridentEntity>> ILUSI_HOMING_TRIDENT =
            ENTITIES.register("ilusi_homing_trident", () -> EntityType.Builder
                    .<IlusiHomingTridentEntity>of(IlusiHomingTridentEntity::new, MobCategory.MISC)
                    .sized(0.5F, 0.5F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("ilusi_homing_trident"));

    // 法术弹射物（铁魔法/诡厄巫法的可见弹射体）
    public static final RegistryObject<EntityType<SpellProjectile>> SPELL_PROJECTILE =
            ENTITIES.register("spell_projectile", () -> EntityType.Builder
                    .<SpellProjectile>of(SpellProjectile::new, MobCategory.MISC)
                    .sized(0.3F, 0.3F)
                    .clientTrackingRange(64)
                    .updateInterval(1)
                    .build("spell_projectile"));

    public static void register(IEventBus modBus) {
        ENTITIES.register(modBus);
    }
}
