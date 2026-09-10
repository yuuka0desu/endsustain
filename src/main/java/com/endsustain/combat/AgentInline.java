package com.endsustain.combat;

import net.minecraft.world.entity.LivingEntity;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Agent 内联通道（JVM 内执行，零外部依赖）：
 * 外部 Attacher 不可用时（java.home 无 bin/java、PATH 无 java、attach 被禁等），
 * 黑盒四级降级中「不依赖 retransform」的部分在此直接执行：
 *   1) 写槽：定位 static void write(X, float, int, long) → 进度拉满 + 相位置二
 *   2) 锚点清空：静态 Map + 无参方法名含 reassert/reanimate/revive/anchor → clear
 *   3) 破壳直写：黑盒类 static float 槽/堆外地址 → Unsafe 直写伪造进度
 * 类定位方式：从目标实体的 codeSource jar 枚举类名 → 目标类加载器 Class.forName →
 * 结构特征匹配（与 KillAgent 同规则，零目标符号）。
 * retransform（读拦截/判定重写）无法在 JVM 内实现——由外部 agent 通道负责。
 */
public final class AgentInline {
    /** 写槽多形态（与 KillAgent 同策略：伪装自然推进 + 随机完成值）。 */
    private static final float[][] PHASE_LEAD = {{99.6f, 1}, {99.8f, 1}, {99.9f, 1}, {99.7f, 1}};
    private static final float[] PROGRESS = {100.0f, 100.1f, 100.25f, 100.5f, 101.0f, 100.01f, 100.75f};

    private AgentInline() {
    }

    /** mods 目录类列表缓存（首次扫描后复用，mods 目录运行期不变）。 */
    private static volatile List<Class<?>> CLASS_CACHE;

    /**
     * 内联执行 agent 层可落地部分。每目标一次（由调用方与 AgentBridge 共用放行节奏）。
     * @return 是否有任何动作执行
     */
    public static boolean runInline(LivingEntity target) {
        boolean hit = false;
        try {
            List<Class<?>> candidates = cachedClasses(target);
            Class<?> living = net.minecraft.world.entity.LivingEntity.class;

            // 1) 写槽
            Method writer = locateSlotWriter(candidates, living, target);
            if (writer != null) {
                java.util.Random rnd = new java.util.Random(System.nanoTime());
                try {
                    float[] lead = PHASE_LEAD[rnd.nextInt(PHASE_LEAD.length)];
                    writer.invoke(null, target, lead[0], (int) lead[1],
                            System.currentTimeMillis() - rnd.nextInt(4000));
                    float progress = PROGRESS[rnd.nextInt(PROGRESS.length)];
                    writer.invoke(null, target, progress, 2,
                            System.currentTimeMillis() + rnd.nextInt(2000) - 1000);
                    hit = true;
                } catch (Throwable ignored) {
                }
            }

            // 2) 锚点清空
            for (Class<?> c : candidates) {
                try {
                    if (!hasStaticMap(c) || !hasAnchorStyleMethod(c)) continue;
                    for (Field f : c.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())
                                && Map.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            Object map = f.get(null);
                            if (map instanceof Map<?, ?> mm) {
                                mm.clear();
                                hit = true;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                }
            }

            // 3) 破壳直写（黑盒类 static float 槽 → Unsafe）
            hit |= smashStaticFloats(candidates);

        } catch (Throwable ignored) {
        }
        return hit;
    }

    /** 通用关键词粗筛（类名字符串级，免去无关类 Class.forName 开销）。 */
    private static boolean quickFilter(String name) {
        String n = name.toLowerCase();
        return n.contains("process") || n.contains("gate") || n.contains("anchor")
                || n.contains("guard") || n.contains("surface") || n.contains("blackbox")
                || n.contains("native") || n.contains("slot") || n.contains("existence")
                || n.contains("transform") || n.contains("codec");
    }

    /** 目标实体 codeSource 所在 mods 目录的全部 jar 类枚举 + 目标类加载器 Class.forName。
     *  黑盒防御类可能位于与实体类不同的 mod jar（如实体在 goety、黑盒在 endertarget），
     *  因此扫描整个 mods 目录而非仅实体自身 jar。结果静态缓存 + 关键词粗筛。 */
    private static List<Class<?>> cachedClasses(LivingEntity target) {
        List<Class<?>> cached = CLASS_CACHE;
        if (cached != null) return cached;
        List<Class<?>> classes = new ArrayList<>();
        try {
            java.security.CodeSource source = target.getClass().getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) return classes;
            java.net.URI uri = source.getLocation().toURI();
            java.nio.file.Path jarPath;
            try {
                jarPath = java.nio.file.Path.of(uri);
            } catch (Throwable t) {
                return classes;
            }
            java.nio.file.Path modsDir = jarPath.getParent();
            if (modsDir == null || !java.nio.file.Files.isDirectory(modsDir)) return classes;
            ClassLoader loader = target.getClass().getClassLoader();
            List<java.nio.file.Path> jars = new ArrayList<>();
            try (var stream = java.nio.file.Files.list(modsDir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".jar"))
                        .forEach(jars::add);
            }
            for (java.nio.file.Path jar : jars) {
                List<String> names = new ArrayList<>();
                try (JarFile jf = new JarFile(jar.toFile())) {
                    java.util.Enumeration<JarEntry> entries = jf.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry entry = entries.nextElement();
                        String name = entry.getName();
                        if (!name.endsWith(".class") || name.contains("$")) continue;
                        names.add(name.substring(0, name.length() - 6).replace('/', '.'));
                    }
                } catch (Throwable ignored) {
                    continue;
                }
                for (String name : names) {
                    if (!quickFilter(name)) continue;
                    try {
                        classes.add(Class.forName(name, false, loader));
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        CLASS_CACHE = classes;
        return classes;
    }

    /** 槽写入器：static void write(X, float, int, long)，声明类含 native 或 Unsafe 特征。 */
    private static Method locateSlotWriter(List<Class<?>> candidates, Class<?> living,
                                           LivingEntity target) {
        for (Class<?> c : candidates) {
            try {
                if (!(hasNative(c) || hasStaticUnsafe(c))) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() != void.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (p[1] != float.class || p[2] != int.class || p[3] != long.class) continue;
                    if (p[0] == Object.class || p[0] == living || p[0].isInstance(target)) {
                        m.setAccessible(true);
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 破壳直写：黑盒类 static float 字段值域像进度槽 → Unsafe 写随机进度。 */
    private static boolean smashStaticFloats(List<Class<?>> candidates) {
        boolean hit = false;
        try {
            Class<?> uc = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = uc.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            Object unsafe = theUnsafe.get(null);
            Method getFloat = uc.getMethod("getFloat", Object.class, long.class);
            Method putFloat = uc.getMethod("putFloat", Object.class, long.class, float.class);
            Method staticOffset = uc.getMethod("staticFieldOffset", Field.class);
            Method staticBase = uc.getMethod("staticFieldBase", Field.class);
            java.util.Random rnd = new java.util.Random(System.nanoTime());

            for (Class<?> c : candidates) {
                try {
                    if (!(hasNative(c) || hasStaticUnsafe(c))) continue;
                    for (Field f : c.getDeclaredFields()) {
                        if (!Modifier.isStatic(f.getModifiers())) continue;
                        if (f.getType() != float.class) continue;
                        f.setAccessible(true);
                        Object base = staticBase.invoke(unsafe, f);
                        if (base == null) continue;
                        long offset = (Long) staticOffset.invoke(unsafe, f);
                        float cur = (Float) getFloat.invoke(unsafe, base, offset);
                        if (cur >= 0.0F && cur <= 1.0E7F && cur < 100.0F) {
                            float smash = PROGRESS[rnd.nextInt(PROGRESS.length)];
                            putFloat.invoke(unsafe, base, offset, smash);
                            hit = true;
                        }
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
        }
        return hit;
    }

    private static boolean hasNative(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (Modifier.isNative(m.getModifiers())) return true;
        }
        return false;
    }

    private static boolean hasStaticUnsafe(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) continue;
            String t = f.getType().getName();
            if (t.equals("sun.misc.Unsafe") || t.equals("jdk.internal.misc.Unsafe")) return true;
        }
        return false;
    }

    private static boolean hasStaticMap(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers())
                    && Map.class.isAssignableFrom(f.getType())) return true;
        }
        return false;
    }

    private static boolean hasAnchorStyleMethod(Class<?> c) {
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers()) || m.getParameterCount() != 0) continue;
            String n = m.getName().toLowerCase();
            if (n.contains("reassert") || n.contains("reanimate")
                    || n.contains("revive") || n.contains("anchor")) {
                return true;
            }
        }
        return false;
    }
}
