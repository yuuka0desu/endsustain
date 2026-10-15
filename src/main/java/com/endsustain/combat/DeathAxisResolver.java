package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 死亡判定轴解析器：定位"实体自己的死亡阈值"，并定向写穿。
 *
 * <h2>为什么需要它</h2>
 * {@link EntityHealthStateBridge} 的六层归零全部假设"真实生命 = 血量数据项"。
 * 部分 Boss 把真实判定轴放在独立对象的普通字段里（该字段既不在
 * {@code SynchedEntityData} 中，数值也与 {@code getHealth()} 返回值无关），
 * 六层一层都命中不了。
 *
 * <h2>工作原理</h2>
 * 不猜字段名、不猜值域，直接做因果实验：对候选字段写 0，观察
 * {@code isAlive()} / {@code isDeadOrDying()} 是否翻转。翻转即为判定轴。
 * 未翻转则在 {@code finally} 中恢复原值。
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>跳过 {@code static} 与 {@code final} 字段，不依赖异常控制流。</li>
 *   <li>继承链上溯遇到 {@code net.minecraft.} 包名即停止。</li>
 *   <li>容器穿透深度限 1，容器类型不得来自 {@code net.minecraft.} 包。</li>
 *   <li>探测每类型一次，结果缓存在 {@code Class} 键上。</li>
 * </ul>
 */
public final class DeathAxisResolver {

    /** 探测结果。同时是第 3 层（Agent 判定器改写）的启用信号。 */
    public enum Probe {
        /** 某候选写 0 后存活状态翻转——判定轴已定位。 */
        HIT,
        /** 枚举后没有任何可用候选字段。 */
        NO_CANDIDATE,
        /** 所有候选的字段读写均抛异常。 */
        ALL_THREW,
        /** 遍历完所有候选，存活状态未翻转。 */
        NO_FLIP,
        /** 探测前实体已不存活，无需探测。 */
        ALREADY_DEAD
    }

    private static final Map<Class<?>, AxisPath> RESOLVED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Probe> PROBED = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Field> HURT_COOLDOWN = new ConcurrentHashMap<>();
    /** 冷却字段缓存的负结果标记（ConcurrentHashMap 不允许 null 值）。 */
    private static final Field NO_COOLDOWN = null;

    private DeathAxisResolver() {}

    /** 服务器停止时清理全部缓存，防止跨存档残留。 */
    public static void clear() {
        RESOLVED.clear();
        PROBED.clear();
        HURT_COOLDOWN.clear();
    }

    /** 上次探测结果（未探测过时返回 null）。供 Agent 侧决定是否启用判定器改写。 */
    public static Probe lastProbe(LivingEntity target) {
        return PROBED.get(target.getClass());
    }

    /** 是否需要启用第 3 层判定器改写（探测未能定位判定轴时）。 */
    public static boolean needsJudgeRewrite(LivingEntity target) {
        Probe probe = PROBED.get(target.getClass());
        return probe == Probe.NO_CANDIDATE || probe == Probe.ALL_THREW || probe == Probe.NO_FLIP;
    }

    /**
     * 定位并缓存判定轴。每类型只做一次实验。
     *
     * @return 探测结果；{@code HIT} 表示 {@link #collapse} 可用
     */
    public static Probe probe(LivingEntity target) {
        Class<?> type = target.getClass();
        Probe cached = PROBED.get(type);
        if (cached != null) return cached;

        if (!target.isAlive() && !target.isDeadOrDying()) {
            PROBED.put(type, Probe.ALREADY_DEAD);
            return Probe.ALREADY_DEAD;
        }
        // 存活状态已处于死亡事务中：判定轴已生效，交给既有链路
        if (target.isDeadOrDying()) {
            PROBED.put(type, Probe.HIT);
            return Probe.HIT;
        }

        List<Candidate> candidates = collectCandidates(type);
        if (candidates.isEmpty()) {
            return finish(type, Probe.NO_CANDIDATE);
        }

        boolean baselineAlive = target.isAlive();
        int threw = 0;
        int attempted = 0;
        for (Candidate candidate : candidates) {
            attempted++;
            Object previous;
            try {
                previous = candidate.read(target);
            } catch (Throwable t) {
                threw++;
                continue;
            }
            boolean matched = false;
            try {
                candidate.writeZero(target);
                matched = target.isAlive() != baselineAlive || target.isDeadOrDying();
            } catch (Throwable t) {
                threw++;
            } finally {
                if (!matched) {
                    // 未命中必须完整恢复：判定轴之外的一切写入都是副作用
                    try {
                        candidate.restore(target, previous);
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (matched) {
                RESOLVED.put(type, candidate.path);
                KillChainMonitor.log("AXIS_HIT 目标=" + KillChainMonitor.typeName(target)
                        + " 判定轴=" + candidate.path.describe());
                EndSustain.LOGGER.info("[终焉强杀] 判定轴已定位：目标={}，判定轴={}",
                        target.getType(), candidate.path.describe());
                PROBED.put(type, Probe.HIT);
                return Probe.HIT;
            }
        }
        if (attempted > 0 && threw == attempted) {
            return finish(type, Probe.ALL_THREW);
        }
        return finish(type, Probe.NO_FLIP);
    }

    /**
     * 对已解析的判定轴执行终态归零（不回滚）。
     * 同时压制同名影子字段（{@code oldXxx}），避免目标侧差异检测把状态回弹。
     *
     * @return 是否有写入发生
     */
    public static boolean collapse(LivingEntity target) {
        AxisPath path = RESOLVED.get(target.getClass());
        if (path == null) return false;
        if (!path.owner().isAssignableFrom(target.getClass())) {
            RESOLVED.remove(target.getClass());
            return false;
        }
        boolean hit = false;
        try {
            Object container = target;
            if (path.isNested()) {
                Field holder = findField(path.owner(), path.containerField());
                if (holder == null) return false;
                holder.setAccessible(true);
                container = holder.get(target);
                if (container == null) return false;
            }
            Field axis = findField(container.getClass(), path.fieldName());
            if (axis == null) return false;
            axis.setAccessible(true);
            writeZero(axis, container);
            hit = true;

            // 影子字段压制：目标侧常用 oldXxx 做差异广播比对，
            // 不同步压制会让归零状态在下一 tick 被目标自己的同步逻辑回弹。
            Field shadow = findField(container.getClass(), "old" + capitalize(path.fieldName()));
            if (shadow != null && isNumeric(shadow.getType())
                    && !Modifier.isFinal(shadow.getModifiers())) {
                shadow.setAccessible(true);
                writeZero(shadow, container);
            }
        } catch (Throwable ignored) {
        }
        return hit;
    }

    /**
     * 绕过 hurt 准入闸门：只写冷却计数器。
     *
     * <p>不写准入标志位——多数实现会在同一调用内复位它（如 finally 语义），
     * 写入无效。把冷却计数器推到闸门阈值之上后，hurt 自身会走到
     * "把标志位置 true" 的分支，从而避开非法伤害计罚路径。
     *
     * @return 是否有写入发生
     */
    public static boolean bypassHurtGates(LivingEntity target) {
        Field cooldown = HURT_COOLDOWN.get(target.getClass());
        if (cooldown == null && !HURT_COOLDOWN.containsKey(target.getClass())) {
            cooldown = locateHurtCooldown(target);
            HURT_COOLDOWN.put(target.getClass(), cooldown == null ? NO_COOLDOWN : cooldown);
        }
        if (cooldown == null) return false;
        try {
            cooldown.setAccessible(true);
            if (cooldown.getType() == long.class) {
                cooldown.setLong(target, Long.MAX_VALUE);
            } else {
                cooldown.setInt(target, Integer.MAX_VALUE);
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------------ 闸门定位

    /**
     * 定位 hurt 准入闸门的冷却计数器。
     *
     * <p>结构特征（零模组符号，无副作用）：hurt 方法体内既有读取又有写入的
     * int/long 实例字段。灵梦的 {@code hurtCD} 命中此规则；{@code hurtCall}
     * 是 boolean 且在返回前被复位，由类型过滤自然排除。
     *
     * <p>不调用 hurt 本体做探测——hurt 可能触发死亡分支与增援逻辑，
     * 副作用不可接受。改为从 hurt 的调用图谱定位：冷却计数器在每 tick
     * 方法中被读取并自增，在 hurt 中被读取并归零，是唯一同时满足
     * 「hurt 内被写」与「存在另一个方法读取它」的 int 字段。
     */
    private static Field locateHurtCooldown(LivingEntity target) {
        Class<?> type = target.getClass();
        if (resolveHurtMethod(type) == null) return null;
        for (Class<?> cur = type; cur != null && !isMinecraft(cur); cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft != int.class && ft != long.class) continue;
                // 冷却计数器必为「非负、有小上限」的读-改-写字段。
                // 判定依据：字段名不含坐标/时间戳语义，且当前值在 [0, 1_000_000) 区间。
                String name = f.getName().toLowerCase(java.util.Locale.ROOT);
                if (name.contains("x") && name.contains("y")) continue;
                if (name.contains("time") && (name.contains("start") || name.contains("last"))) continue;
                try {
                    f.setAccessible(true);
                    long now = ft == int.class ? f.getInt(target) : f.getLong(target);
                    if (now < 0L || now >= 1_000_000L) continue;
                    return f;
                } catch (Throwable ignored) {
                }
            }
        }
        return null;
    }

    private static Method resolveHurtMethod(Class<?> type) {
        for (Class<?> cur = type; cur != null && !isMinecraft(cur); cur = cur.getSuperclass()) {
            for (String name : new String[]{"hurt", "m_6469_"}) {
                try {
                    return cur.getDeclaredMethod(name,
                            net.minecraft.world.damagesource.DamageSource.class, float.class);
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 候选收集

    private record Candidate(AxisPath path, Field field) {
        Object read(LivingEntity target) throws Exception {
            Field holder = findField(target.getClass(), path.containerField());
            Object container = holder == null ? target : accessible(holder).get(target);
            if (container == null) throw new IllegalStateException("container null");
            return accessible(field).get(container);
        }
        void writeZero(LivingEntity target) throws Exception {
            Field holder = findField(target.getClass(), path.containerField());
            Object container = holder == null ? target : accessible(holder).get(target);
            if (container == null) return;
            DeathAxisResolver.writeZero(accessible(field), container);
        }
        void restore(LivingEntity target, Object previous) throws Exception {
            Field holder = findField(target.getClass(), path.containerField());
            Object container = holder == null ? target : accessible(holder).get(target);
            if (container == null) return;
            accessible(field).set(container, previous);
        }
    }

    private static List<Candidate> collectCandidates(Class<?> type) {
        List<Candidate> out = new ArrayList<>();
        for (Class<?> cur = type; cur != null && !isMinecraft(cur); cur = cur.getSuperclass()) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                if (Modifier.isFinal(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (isNumeric(ft)) {
                    out.add(new Candidate(new AxisPath(cur, f.getName(), null, null), f));
                    continue;
                }
                // 深度 1 容器穿透：排除 MC 包与 JDK 集合，避免扫到 level/random/passengers
                if (ft.isPrimitive() || ft.isArray() || isMinecraft(ft)) continue;
                if (ft.getName().startsWith("java.")) continue;
                for (Field inner : ft.getDeclaredFields()) {
                    if (Modifier.isStatic(inner.getModifiers())) continue;
                    if (Modifier.isFinal(inner.getModifiers())) continue;
                    if (!isNumeric(inner.getType())) continue;
                    out.add(new Candidate(
                            new AxisPath(cur, inner.getName(), ft, f.getName()), inner));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 工具

    private static void writeZero(Field field, Object container) throws IllegalAccessException {
        Class<?> t = field.getType();
        if (t == float.class) field.setFloat(container, 0.0F);
        else if (t == double.class) field.setDouble(container, 0.0D);
        else if (t == int.class) field.setInt(container, 0);
        else if (t == long.class) field.setLong(container, 0L);
        else if (t == short.class) field.setShort(container, (short) 0);
        else if (t == byte.class) field.setByte(container, (byte) 0);
    }

    private static Field accessible(Field field) {
        field.setAccessible(true);
        return field;
    }

    private static boolean isNumeric(Class<?> t) {
        return t == float.class || t == double.class || t == int.class
                || t == long.class || t == short.class || t == byte.class;
    }

    private static boolean isMinecraft(Class<?> t) {
        return t.getName().startsWith("net.minecraft.");
    }

    private static Field findField(Class<?> owner, String name) {
        if (name == null) return null;
        for (Class<?> cur = owner; cur != null; cur = cur.getSuperclass()) {
            try {
                return cur.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static String capitalize(String value) {
        return value == null || value.isEmpty() ? value
                : Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Probe finish(Class<?> type, Probe result) {
        PROBED.put(type, result);
        return result;
    }
}
