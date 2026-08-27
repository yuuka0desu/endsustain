package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves the synchronized value that backs an entity's public health view.
 * This keeps terminal damage compatible with entities that store health outside
 * LivingEntity's vanilla field without depending on a particular implementation.
 */
public final class EntityHealthStateBridge {
    private static final float EPSILON = 0.0001F;
    private static final Map<Class<?>, List<EntityDataAccessor<?>>> ACCESSORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, EntityDataAccessor<?>> RESOLVED_HEALTH = new ConcurrentHashMap<>();

    private EntityHealthStateBridge() {}

    public static boolean zeroActiveHealth(LivingEntity target) {
        if (target.getHealth() <= 0.0F) return true;

        if (VANILLA_HEALTH != null) {
            try {
                target.getEntityData().set(VANILLA_HEALTH, 0.0F);
                if (target.getHealth() <= 0.0F) return true;
            } catch (RuntimeException ignored) {
            }
        }

        EntityDataAccessor<?> cached = RESOLVED_HEALTH.get(target.getClass());
        if (cached != null && tryAccessor(target, cached)) return true;

        for (EntityDataAccessor<?> accessor : ACCESSORS.computeIfAbsent(
                target.getClass(), EntityHealthStateBridge::collectAccessors)) {
            if (accessor == cached || !matchesCurrentHealth(target, accessor)) continue;
            if (tryAccessor(target, accessor)) {
                RESOLVED_HEALTH.put(target.getClass(), accessor);
                return true;
            }
        }
        return target.getHealth() <= 0.0F;
    }

    /**
     * 把实体的生命同步数据直接写为指定值，绕过 setHealth 覆写与 mixin 拦截。
     * 固定使用原版生命 accessor（Float 类型），不做泛化匹配，
     * 避免把数值误写入其他 Double 型同步数据导致类型污染。
     */
    public static boolean writeActiveHealth(LivingEntity target, float value) {
        EntityDataAccessor<Float> vanilla = VANILLA_HEALTH;
        if (vanilla == null) return false;
        try {
            target.getEntityData().set(vanilla, Math.max(0.0F, value));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 原版生命同步 accessor：生产环境 SRG 名 f_20961_，开发环境 DATA_HEALTH_ID。 */
    private static final EntityDataAccessor<Float> VANILLA_HEALTH = resolveVanillaHealth();

    private static EntityDataAccessor<Float> resolveVanillaHealth() {
        for (String name : new String[]{"f_20961_", "DATA_HEALTH_ID"}) {
            try {
                Field field = LivingEntity.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof EntityDataAccessor<?> accessor) {
                    return (EntityDataAccessor<Float>) accessor;
                }
            } catch (Throwable ignored) {
            }
        }
        EndSustain.LOGGER.warn("未能解析原版生命同步字段，终焉生命写入退化为 setHealth");
        return null;
    }

    private static boolean tryAccessor(LivingEntity target, EntityDataAccessor<?> accessor) {
        SynchedEntityData data = target.getEntityData();
        Object previous;
        try {
            previous = read(data, accessor);
        } catch (RuntimeException exception) {
            return false;
        }
        if (!(previous instanceof Float) && !(previous instanceof Double)) return false;
        if (!sameNumber(previous, target.getHealth())) return false;

        Object zero;
        if (previous instanceof Double) {
            zero = Double.valueOf(0.0D);
        } else {
            zero = Float.valueOf(0.0F);
        }
        try {
            write(data, accessor, zero);
            if (target.getHealth() <= 0.0F) return true;
            write(data, accessor, previous);
        } catch (RuntimeException exception) {
            try {
                write(data, accessor, previous);
            } catch (RuntimeException ignored) {
                // A failed probe is left to the entity's normal synchronization on the next tick.
            }
        }
        return false;
    }

    private static boolean matchesCurrentHealth(LivingEntity target, EntityDataAccessor<?> accessor) {
        try {
            Object value = read(target.getEntityData(), accessor);
            if (!(value instanceof Float) && !(value instanceof Double)) return false;
            double number = ((Number) value).doubleValue();
            return number >= 0.0D
                    && number <= Math.max(1.0D, target.getMaxHealth())
                    && sameNumber(value, target.getHealth());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean sameNumber(Object value, float health) {
        return Math.abs(((Number) value).doubleValue() - health) <= EPSILON;
    }

    private static List<EntityDataAccessor<?>> collectAccessors(Class<?> entityClass) {
        List<EntityDataAccessor<?>> accessors = new ArrayList<>();
        for (Class<?> type = entityClass; type != null && LivingEntity.class.isAssignableFrom(type);
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers())
                        || !EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof EntityDataAccessor<?> accessor && !accessors.contains(accessor)) {
                        accessors.add(accessor);
                    }
                } catch (ReflectiveOperationException | RuntimeException exception) {
                    EndSustain.LOGGER.debug("跳过不可读取的实体同步字段 {}.{}",
                            type.getName(), field.getName());
                }
            }
        }
        return List.copyOf(accessors);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object read(SynchedEntityData data, EntityDataAccessor<?> accessor) {
        return data.get((EntityDataAccessor) accessor);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void write(SynchedEntityData data, EntityDataAccessor<?> accessor, Object value) {
        data.set((EntityDataAccessor) accessor, value);
    }
}
