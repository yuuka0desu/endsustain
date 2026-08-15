package com.endsustain.compat.goety;

import com.endsustain.EndSustain;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Goety 兼容子模块 —— 通过反射生成 Goety 原版弹射物实体。
 * 仅在 goety 已加载时被 CompatHandler 调用。
 */
public class GoetyCompat {

    private static Class<?> modEntityTypeClass;
    private static Method    entityTypeGetMethod; // RegistryObject.get()
    private static Method    setOwnerMethod;
    private static Method    setTargetMethod;
    private static Method    setExtraDamageMethod;

    // 聚晶名 → Goety ModEntityType 字段名
    private static final java.util.Map<String, String> CRYSTAL_MAP = java.util.Map.of(
            "rending_crystal",    "SOUL_BULLET",
            "cult_crystal",       "SOUL_BOLT",
            "corrupting_crystal", "MAGIC_BOLT",
            "wither_crystal",     "WITHER_BOLT",
            "binding_crystal",    "MAGIC_FIRE",
            "shocking_crystal",   "SPELL_LIGHTNING_BOLT"
    );

    public static void init(IEventBus modBus) {
        try {
            modEntityTypeClass = Class.forName("com.Polarice3.Goety.common.entities.ModEntityType");
            entityTypeGetMethod = Class.forName("net.minecraftforge.registries.RegistryObject")
                    .getMethod("get");

            Class<?> spellEntityClass = Class.forName(
                    "com.Polarice3.Goety.common.entities.projectiles.SpellEntity");
            setOwnerMethod = spellEntityClass.getMethod("setOwner", LivingEntity.class);
            setTargetMethod = spellEntityClass.getMethod("setTarget", LivingEntity.class);
            setExtraDamageMethod = spellEntityClass.getMethod("setExtraDamage", float.class);

            EndSustain.LOGGER.info("[终焉维系] Goety API 绑定成功");
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[终焉维系] Goety API 绑定失败: {}", t.toString());
        }
    }

    /** 由 FinaleEndsustainEntity.castGoetyCrystal 调用 */
    public static boolean castCrystal(FinaleEndsustainEntity boss, Player target, String crystalId) {
        if (modEntityTypeClass == null) return false;
        try {
            String fieldName = CRYSTAL_MAP.get(crystalId);
            if (fieldName == null) return false;

            Field field = modEntityTypeClass.getField(fieldName);
            Object regObj = field.get(null);
            Object entityType = entityTypeGetMethod.invoke(regObj);

            Object entity = createOriginalGoetyCrystalEntity(boss, target, crystalId, entityType);
            if (entity == null) return false;

            applyIfPresent(entity, "setOwner", new Class<?>[]{LivingEntity.class}, boss);
            applyIfPresent(entity, "setTarget", new Class<?>[]{LivingEntity.class}, target);
            applyIfPresent(entity, "setExtraDamage", new Class<?>[]{float.class}, 10.0F);
            applyIfPresent(entity, "setDamage", new Class<?>[]{float.class}, 10.0F);
            correctProjectileMotion(entity, boss, target, crystalId);

            boss.level().addFreshEntity((net.minecraft.world.entity.Entity) entity);

            EndSustain.LOGGER.debug("[终焉维系] Goety 聚晶施放: {}", crystalId);
            return true;
        } catch (Throwable t) {
            EndSustain.LOGGER.warn("[终焉维系] Goety 聚晶施放失败 {}: {}", crystalId, t.toString());
            return false;
        }
    }

    private static Object createOriginalGoetyCrystalEntity(FinaleEndsustainEntity boss, Player target,
                                                           String crystalId, Object entityType) throws Exception {
        Vec3 from = boss.getEyePosition();
        Vec3 to = target.getEyePosition();
        Vec3 dir = to.subtract(from).normalize();
        Level level = boss.level();

        return switch (crystalId) {
            case "rending_crystal" -> {
                Class<?> cls = Class.forName("com.Polarice3.Goety.common.entities.projectiles.SoulBullet");
                Object entity = cls.getConstructor(Level.class, LivingEntity.class, double.class, double.class, double.class)
                        .newInstance(level, boss, dir.x, dir.y, dir.z);
                ((net.minecraft.world.entity.Entity) entity).setPos(boss.getX(), boss.getEyeY() - 0.3D, boss.getZ());
                yield entity;
            }
            case "cult_crystal" -> {
                Class<?> cls = Class.forName("com.Polarice3.Goety.common.entities.projectiles.SoulBolt");
                Object entity = cls.getConstructor(LivingEntity.class, double.class, double.class, double.class, Level.class)
                        .newInstance(boss, dir.x, dir.y, dir.z, level);
                ((net.minecraft.world.entity.Entity) entity).setPos(boss.getX(), boss.getEyeY() - 0.3D, boss.getZ());
                yield entity;
            }
            case "corrupting_crystal" -> {
                Class<?> cls = Class.forName("com.Polarice3.Goety.common.entities.projectiles.MagicBolt");
                Object entity = cls.getConstructor(Level.class, LivingEntity.class, double.class, double.class, double.class)
                        .newInstance(level, boss, dir.x, dir.y, dir.z);
                ((net.minecraft.world.entity.Entity) entity).setPos(boss.getX(), boss.getEyeY() - 0.3D, boss.getZ());
                yield entity;
            }
            case "wither_crystal" -> {
                Class<?> cls = Class.forName("com.Polarice3.Goety.common.entities.projectiles.WitherBolt");
                Object entity = cls.getConstructor(LivingEntity.class, double.class, double.class, double.class, Level.class)
                        .newInstance(boss, dir.x, dir.y, dir.z, level);
                ((net.minecraft.world.entity.Entity) entity).setPos(boss.getX(), boss.getEyeY() - 0.3D, boss.getZ());
                yield entity;
            }
            case "binding_crystal" -> {
                Class<?> cls = Class.forName("com.Polarice3.Goety.common.entities.projectiles.MagicFire");
                yield cls.getConstructor(Level.class, Vec3.class, LivingEntity.class)
                        .newInstance(level, target.position(), boss);
            }
            case "shocking_crystal" -> {
                Method createMethod = entityType.getClass().getMethod("create", Level.class);
                Object entity = createMethod.invoke(entityType, level);
                ((net.minecraft.world.entity.Entity) entity).setPos(target.getX(), target.getY(), target.getZ());
                yield entity;
            }
            default -> {
                Method createMethod = entityType.getClass().getMethod("create", Level.class);
                Object entity = createMethod.invoke(entityType, level);
                ((net.minecraft.world.entity.Entity) entity).setPos(boss.getX(), boss.getEyeY() - 0.3D, boss.getZ());
                yield entity;
            }
        };
    }

    private static void correctProjectileMotion(Object entity, FinaleEndsustainEntity boss, Player target, String crystalId) {
        if ("binding_crystal".equals(crystalId) || "shocking_crystal".equals(crystalId)) return;
        if (!(entity instanceof net.minecraft.world.entity.Entity mcEntity)) return;
        Vec3 from = mcEntity.position();
        Vec3 to = target.getEyePosition();
        Vec3 motion = to.subtract(from).normalize().scale(1.25D);
        mcEntity.setDeltaMovement(motion);
        float yaw = (float)(Math.atan2(motion.x, motion.z) * (180.0D / Math.PI));
        float pitch = (float)(Math.asin(-motion.normalize().y) * (180.0D / Math.PI));
        mcEntity.setYRot(yaw);
        mcEntity.setXRot(pitch);
        mcEntity.hurtMarked = true;
    }

    private static void applyIfPresent(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(methodName, parameterTypes);
            method.invoke(target, args);
        } catch (Throwable ignored) {
            // 不同 Goety 弹体的 API 不完全一致；不存在的方法直接跳过，保留原版实体特效。
        }
    }
}
