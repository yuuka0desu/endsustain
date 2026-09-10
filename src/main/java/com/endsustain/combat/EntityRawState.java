package com.endsustain.combat;

import net.minecraft.world.entity.Entity;

import java.lang.reflect.Field;

/**
 * 实体原始状态直读（绕开目标覆写的方法）：
 * 部分防御实体覆写 isRemoved()/getHealth() 等方法返回伪造值，
 * 使基于方法调用的判定全部失真——此处直接读字段/数据项。
 */
public final class EntityRawState {
    /** Entity.removalReason 字段（双名 Mojmap/SRG，缓存）。 */
    private static final Field REMOVAL_REASON = resolveField(
            net.minecraft.world.entity.Entity.class, "f_146795_", "removalReason");

    private EntityRawState() {
    }

    /** 真实移除状态：直接读 removalReason 字段（绕开 isRemoved() 覆写）。 */
    public static boolean rawRemoved(Entity entity) {
        if (REMOVAL_REASON == null) return entity.isRemoved();
        try {
            return REMOVAL_REASON.get(entity) != null;
        } catch (Throwable ignored) {
            return entity.isRemoved();
        }
    }

    /** 反射直写 removalReason 字段（绕开一切 remove/setRemoved 覆写），返回是否写成功。 */
    public static boolean forceRawRemoved(Entity entity) {
        if (REMOVAL_REASON == null) return false;
        try {
            REMOVAL_REASON.set(entity, Entity.RemovalReason.KILLED);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 原版生命数据项直读（绕开 getHealth() 覆写）。无法解析时回退 getHealth()。 */
    public static float rawHealth(net.minecraft.world.entity.LivingEntity entity) {
        net.minecraft.network.syncher.EntityDataAccessor<Float> accessor =
                EntityHealthStateBridge.vanillaHealthAccessor(entity);
        if (accessor == null) return entity.getHealth();
        try {
            Object value = entity.getEntityData().get(accessor);
            return value instanceof Number number ? number.floatValue() : entity.getHealth();
        } catch (Throwable ignored) {
            return entity.getHealth();
        }
    }

    private static Field resolveField(Class<?> owner, String srg, String mojmap) {
        for (String name : new String[]{srg, mojmap}) {
            try {
                Field field = owner.getDeclaredField(name);
                field.setAccessible(true);
                return field;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }
}
