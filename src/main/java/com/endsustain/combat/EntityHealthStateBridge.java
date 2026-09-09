package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
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

        EntityDataAccessor<Float> vanilla = healthAccessor(target);
        if (vanilla != null) {
            try {
                target.getEntityData().set(vanilla, 0.0F);
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
     * 多路径绝对击杀 L0：生命归零 + 上限锁定（纯通用，无针对性调用）。
     * 把目标类继承链上全部「值 ≈ 当前生命 或 ≈ 最大生命」的 Float/Double 同步项
     * 直接写 0 且不还原——上限类同步项一并归零后，回血/回滚会被自身钳制到 0，
     * 形成死亡不可逆锁定。返回是否完成至少一次成功写入。
     */
    public static boolean lockHealthAndCapToZero(LivingEntity target) {
        SynchedEntityData data = target.getEntityData();
        float health;
        float maxHealth;
        try {
            health = target.getHealth();
            maxHealth = target.getMaxHealth();
        } catch (Throwable t) {
            // 目标覆写的 getHealth/getMaxHealth 读污染槽自身异常时：
            // health 用 0（匹配已归零生命槽），maxHealth 用极值（上限匹配失效但生命槽仍可命中）
            health = 0.0F;
            maxHealth = Float.MAX_VALUE;
        }
        boolean hit = false;

        EntityDataAccessor<Float> vanilla = healthAccessor(target);
        if (vanilla != null) {
            try {
                data.set(vanilla, 0.0F);
                hit = true;
            } catch (RuntimeException ignored) {
            }
        }

        for (EntityDataAccessor<?> accessor : ACCESSORS.computeIfAbsent(
                target.getClass(), EntityHealthStateBridge::collectAccessors)) {
            Object value;
            try {
                value = data.get(accessor);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!(value instanceof Float) && !(value instanceof Double)) continue;
            double number = ((Number) value).doubleValue();
            boolean matchesHealth = Math.abs(number - (double) health) <= EPSILON;
            boolean matchesMax = Math.abs(number - (double) maxHealth) <= EPSILON;
            if (!matchesHealth && !matchesMax) continue;
            // 按 accessor 声明的 serializer 类型写入：
            // 即使槽内当前值已被污染成 Double（Double 写进 Float 槽），
            // 也强制写回 Float 0，把槽修复为声明类型，杜绝 ClassCastException。
            Object zero = zeroValueFor(accessor);
            if (zero == null) continue;
            try {
                write(data, accessor, zero);
                hit = true;
            } catch (RuntimeException ignored) {
            }
        }
        return hit || target.getHealth() <= 0.0F;
    }

    /**
     * 无效化层 2：字符串校验码生命项归零（纯通用特征匹配）。
     * 特征：String 数据项的值含「目标 UUID 哈希值的十进制表示」子串，
     * 且该子串之后存在 ∈ [123,132] 的偏移编码字符（每字符编码一位数字）。
     * 处理：复用原前缀不变，仅把编码体数字全部重写为 0（生命 0 的合法编码）。
     */
    public static boolean zeroStringCodedHealth(LivingEntity target) {
        SynchedEntityData data = target.getEntityData();
        String hashToken = String.valueOf(target.getUUID().hashCode());
        boolean hit = false;
        for (EntityDataAccessor<?> accessor : ACCESSORS.computeIfAbsent(
                target.getClass(), EntityHealthStateBridge::collectAccessors)) {
            Object value;
            try {
                value = data.get(accessor);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!(value instanceof String string)) continue;
            int idx = string.indexOf(hashToken);
            if (idx < 0) continue;
            int bodyStart = idx + hashToken.length();
            if (bodyStart >= string.length()) continue;
            boolean coded = false;
            for (int i = bodyStart; i < string.length(); i++) {
                char ch = string.charAt(i);
                if (ch >= 123 && ch <= 132) {
                    coded = true;
                    break;
                }
            }
            if (!coded) continue;
            StringBuilder rewritten = new StringBuilder(string);
            for (int i = bodyStart; i < rewritten.length(); i++) {
                char ch = rewritten.charAt(i);
                if (ch >= 123 && ch <= 132) {
                    rewritten.setCharAt(i, (char) 123); // 数字 0 的偏移编码
                }
            }
            try {
                write(data, accessor, rewritten.toString());
                hit = true;
            } catch (RuntimeException ignored) {
            }
        }
        return hit;
    }

    /**
     * 无效化层 3：字节标志项归零（Byte 数据项全部写 0），
     * 使「复活/屏障击杀」类状态判定失效。
     */
    public static boolean zeroByteFlags(LivingEntity target) {
        SynchedEntityData data = target.getEntityData();
        boolean hit = false;
        for (EntityDataAccessor<?> accessor : ACCESSORS.computeIfAbsent(
                target.getClass(), EntityHealthStateBridge::collectAccessors)) {
            Object value;
            try {
                value = data.get(accessor);
            } catch (RuntimeException exception) {
                continue;
            }
            if (!(value instanceof Byte)) continue;
            try {
                write(data, accessor, Byte.valueOf((byte) 0));
                hit = true;
            } catch (RuntimeException ignored) {
            }
        }
        return hit;
    }

    /**
     * 无效化层 4：persistentData 数字键归零（虚拟生命类存储）。
     * 审计 P7 修订：数字类型 tag 全归零会误清坐标/计时器——
     * 改为「键名模式（health/hp/life/blood/virtual）或值域过滤（[0, max(1e6, maxHealth)]）」
     * 二者满足其一才归零，保护无关状态。
     */
    public static boolean zeroPersistentFloats(LivingEntity target) {
        net.minecraft.nbt.CompoundTag tag = target.getPersistentData();
        float maxHealth;
        try {
            maxHealth = target.getMaxHealth();
        } catch (Throwable t) {
            maxHealth = Float.MAX_VALUE;
        }
        double valueCeil = Math.max(1_000_000.0D, (double) maxHealth);
        boolean hit = false;
        for (String key : tag.getAllKeys()) {
            int type = tag.getTagType(key);
            boolean numeric = type == net.minecraft.nbt.Tag.TAG_FLOAT
                    || type == net.minecraft.nbt.Tag.TAG_INT
                    || type == net.minecraft.nbt.Tag.TAG_DOUBLE
                    || type == net.minecraft.nbt.Tag.TAG_LONG;
            if (!numeric) continue;
            boolean healthLike = key.toLowerCase(java.util.Locale.ROOT)
                    .matches(".*(health|hp|life|blood|virtual).*");
            double v;
            switch (type) {
                case net.minecraft.nbt.Tag.TAG_FLOAT -> v = tag.getFloat(key);
                case net.minecraft.nbt.Tag.TAG_INT -> v = tag.getInt(key);
                case net.minecraft.nbt.Tag.TAG_DOUBLE -> v = tag.getDouble(key);
                default -> v = tag.getLong(key);
            }
            if (!healthLike && !(v >= 0.0D && v <= valueCeil)) continue;
            switch (type) {
                case net.minecraft.nbt.Tag.TAG_FLOAT -> tag.putFloat(key, 0.0F);
                case net.minecraft.nbt.Tag.TAG_INT -> tag.putInt(key, 0);
                case net.minecraft.nbt.Tag.TAG_DOUBLE -> tag.putDouble(key, 0.0D);
                default -> tag.putLong(key, 0L);
            }
            hit = true;
        }
        return hit;
    }

    /**
     * 无效化层 5：反向找生命值字段。
     * 从 getHealth() 的返回值反向匹配：扫描目标类继承链上全部非静态
     * Float/Integer/Long 实例字段，值 ≈ 当前生命 或 ≈ 最大生命 → 写 0。
     * int/long 采用「探测写 → 验证 → 否则回滚原值」模式（审计 P7 安全子集）；
     * 不触碰 double/Double 字段：部分实体内部对 Double 字段做 Float 强转，
     * 归零会破坏其不变量并触发 ClassCastException（tutm 实战教训）。
     * 覆盖「生命存储在实例字段（而非数据项）」的实体。
     */
    public static boolean zeroInstanceHealthFields(LivingEntity target) {
        float health;
        float maxHealth;
        try {
            health = target.getHealth();
            maxHealth = target.getMaxHealth();
        } catch (Throwable t) {
            // 目标覆写的 getHealth/getMaxHealth 自身异常时，跳过本层（由其它层兜底）
            return false;
        }
        boolean hit = false;
        for (Class<?> type = target.getClass();
             type != null && LivingEntity.class.isAssignableFrom(type);
             type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Class<?> fieldType = field.getType();
                boolean isFloat = fieldType == float.class || fieldType == Float.class;
                boolean isInt = fieldType == int.class || fieldType == Integer.class;
                boolean isLong = fieldType == long.class || fieldType == Long.class;
                if (!isFloat && !isInt && !isLong) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (!(value instanceof Number number)) continue;
                    double v = number.doubleValue();
                    boolean matchHealth = Math.abs(v - (double) health) <= EPSILON;
                    boolean matchMax = Math.abs(v - (double) maxHealth) <= EPSILON;
                    if (!matchHealth && !matchMax) continue;
                    if (isFloat) {
                        field.set(target, 0.0F);
                        hit = true;
                    } else {
                        // 探测写：写 0 → 验证死亡状态成立；否则回滚原值
                        long prev = number.longValue();
                        if (isInt) {
                            field.setInt(target, 0);
                        } else {
                            field.setLong(target, 0L);
                        }
                        if (target.getHealth() <= 0.0F || target.isDeadOrDying()) {
                            hit = true;
                        } else if (isInt) {
                            field.setInt(target, (int) prev);
                        } else {
                            field.setLong(target, prev);
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        return hit;
    }

    /**
     * 无效化层 6：死亡动画/死亡序列入口。
     * 部分实体覆写 setRemoved/isAlive/die：仅在自身「死亡动画」状态为 true 时
     * 才接受移除，且动画分支内走 super.tick 推进原版 deathTime（20 tick 后自行移除）；
     * 非动画状态下 setRemoved 被完全拒绝（如 tutm 时间管理者）。
     * 通用特征匹配：公开 setter，名称含 DeathAnimation/PlayingDeath/DeathSequence/Dying
     * 且参数为单个 boolean → 反射置 true，使实体进入其正常死亡演出流程。
     */
    public static boolean startDeathAnimation(LivingEntity target) {
        boolean hit = false;
        for (Method method : target.getClass().getMethods()) {
            if (method.getParameterCount() != 1
                    || method.getParameterTypes()[0] != boolean.class) continue;
            String name = method.getName();
            boolean match = name.contains("DeathAnimation")
                    || name.contains("PlayingDeath")
                    || name.contains("DeathSequence")
                    || name.contains("Dying");
            if (!match) continue;
            try {
                method.invoke(target, Boolean.TRUE);
                hit = true;
            } catch (Throwable ignored) {
            }
        }
        return hit;
    }

    /**
     * 通用无效化：对目标执行全部六层归零（各层异常隔离，单层失败不影响其它层），
     * 返回是否有任何写入发生。
     */
    public static boolean neutralize(LivingEntity target) {
        boolean hit = false;
        try {
            hit |= lockHealthAndCapToZero(target);
        } catch (Throwable ignored) {
        }
        try {
            hit |= zeroStringCodedHealth(target);
        } catch (Throwable ignored) {
        }
        try {
            hit |= zeroByteFlags(target);
        } catch (Throwable ignored) {
        }
        try {
            hit |= zeroPersistentFloats(target);
        } catch (Throwable ignored) {
        }
        try {
            hit |= zeroInstanceHealthFields(target);
        } catch (Throwable ignored) {
        }
        try {
            hit |= startDeathAnimation(target);
        } catch (Throwable ignored) {
        }
        return hit;
    }

    /**
     * 把实体的生命同步数据直接写为指定值，绕过 setHealth 覆写与 mixin 拦截。
     * 固定使用原版生命 accessor（Float 类型），不做泛化匹配，
     * 避免把数值误写入其他 Double 型同步数据导致类型污染。
     */
    public static boolean writeActiveHealth(LivingEntity target, float value) {
        EntityDataAccessor<Float> vanilla = healthAccessor(target);
        if (vanilla == null) return false;
        try {
            target.getEntityData().set(vanilla, Math.max(0.0F, value));
            return true;
        } catch (RuntimeException exception) {
            return false;
        }
    }

    /** 原版生命同步 accessor：生产环境 SRG 名 f_20961_，开发环境 DATA_HEALTH_ID。 */
    private static volatile EntityDataAccessor<Float> VANILLA_HEALTH = resolveVanillaHealthByName();

    /**
     * 审计 P6：原版生命槽解析。
     * 1) 按字段名双候选解析；
     * 2) 皆缺时惰性类型回退：扫描 LivingEntity 静态 EntityDataAccessor 字段，
     *    serializer == FLOAT 的候选经「set(0) → getHealth()<=0 命中则缓存，否则回滚」行为探测。
     *    1.20.1 中 Float 数据项仅有生命槽（javap 实证），类型过滤天然安全，探测防未来版本新增。
     */
    private static EntityDataAccessor<Float> healthAccessor(LivingEntity target) {
        EntityDataAccessor<Float> current = VANILLA_HEALTH;
        if (current != null) return current;
        return tryResolveByType(target);
    }

    private static EntityDataAccessor<Float> resolveVanillaHealthByName() {
        for (String name : new String[]{"f_20961_", "DATA_HEALTH_ID"}) {
            try {
                Field field = LivingEntity.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof EntityDataAccessor<?> accessor
                        && accessor.getSerializer() == EntityDataSerializers.FLOAT) {
                    return (EntityDataAccessor<Float>) accessor;
                }
            } catch (Throwable ignored) {
            }
        }
        EndSustain.LOGGER.warn("未能按名称解析原版生命同步字段，将启用类型匹配惰性回退");
        return null;
    }

    private static synchronized EntityDataAccessor<Float> tryResolveByType(LivingEntity target) {
        if (VANILLA_HEALTH != null) return VANILLA_HEALTH;
        for (Field field : LivingEntity.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) continue;
            if (!EntityDataAccessor.class.isAssignableFrom(field.getType())) continue;
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (!(value instanceof EntityDataAccessor<?> accessor)
                        || accessor.getSerializer() != EntityDataSerializers.FLOAT) continue;
                SynchedEntityData data = target.getEntityData();
                Object previous = read(data, accessor);
                write(data, accessor, Float.valueOf(0.0F));
                boolean matched = target.getHealth() <= 0.0F;
                if (!matched) {
                    write(data, accessor, previous);
                    continue;
                }
                VANILLA_HEALTH = (EntityDataAccessor<Float>) accessor;
                return VANILLA_HEALTH;
            } catch (Throwable ignored) {
            }
        }
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

        // 按 accessor 声明的 serializer 类型写入（与探测写回同理：修复类型污染）。
        Object zero = zeroValueFor(accessor);
        if (zero == null) return false;
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

    /**
     * 按 accessor 声明类型返回归零值：serializer 为 FLOAT 时返回 Float 0，
     * 其余类型返回 null（跳过）。绝不按槽内当前值的运行时类型决定写入类型——
     * 槽可能已被其它来源污染（Double 写进 Float 槽）。
     * 1.20.1 原版没有 DOUBLE serializer；自定义 Double 槽交由其它层覆盖。
     */
    private static Object zeroValueFor(EntityDataAccessor<?> accessor) {
        if (accessor.getSerializer() == EntityDataSerializers.FLOAT) return Float.valueOf(0.0F);
        return null;
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
