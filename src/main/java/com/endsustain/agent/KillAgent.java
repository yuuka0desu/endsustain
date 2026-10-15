package com.endsustain.agent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 外部 agent 保险层（强杀链最后兜底）——黑盒拦截平级版。
 *
 * 由独立进程通过 Attach API 加载进游戏 JVM，执行四级降级链：
 *   L1   写槽：static void write(X, float, int, long)（进度拉满 + 相位置二）
 *   L1.5 读拦截（主手段）：retransform 黑盒 Java 侧读方法为常量伪造——
 *        static float f(LivingEntity) → 恒 100.0f（bipush 100; i2f; freturn）
 *        static int   f(LivingEntity) → 恒 2（iconst_2; ireturn）
 *        判定链（shouldDie→phase/progress→读方法）在 Java 层被伪造值翻转，
 *        JNI/native/Unsafe 堆外槽根本不会被触及，字段写 guard 完全无关。
 *   L-c   判定器重写（兜底）：static boolean f(LivingEntity) 方法体恒 true。
 *   L2    复活锚点清空 + 硬移除三级（反射 remove → 实体管理器直删 → Unsafe 直写）。
 *
 * 设计约束：字节码中不出现任何目标模组符号——一切定位均通过运行时结构特征
 * （方法签名、native 修饰、静态字段类型）+ 反射完成。
 * 可逆性：拦截时保存原始字节码，agentmain("restore") 可再次 retransform 还原。
 */
public final class KillAgent {

    private static volatile Instrumentation INST;
    private static volatile Method SLOT_WRITER;
    private static volatile List<Method> SLOT_READERS;
    private static volatile List<Method> JUDGES;
    /** 已改写的类 → 原始字节码（restore 用）。 */
    private static final Map<String, byte[]> ORIGINAL_BYTES = new ConcurrentHashMap<>();

    // 伪造输出字节码（零常量池依赖）
    private static final Object[][] JUDGE_VARIANTS = {
            {new byte[]{(byte) 0x04, (byte) 0xAC}, 1},                    // iconst_1; ireturn
            {new byte[]{(byte) 0x10, 1, (byte) 0xAC}, 1},                 // bipush 1; ireturn
            {new byte[]{0x05, 0x04, 0x64, (byte) 0xAC}, 2},               // iconst_2; iconst_1; isub; ireturn
            {new byte[]{0x04, 0x04, 0x60, 0x04, 0x64, (byte) 0xAC}, 2},   // 1+1-1 = 1
    };

    // 多形态伪造：同一语义值的多种字节码构造（防「精确字节特征」被反针对检测）
    private static final Object[][] READ_PROGRESS_VARIANTS = {
            // {code, max_stack}
            {new byte[]{(byte) 0x10, 100, (byte) 0x86, (byte) 0xAE}, 1},                      // bipush 100; i2f; freturn
            {new byte[]{(byte) 0x10, 50, (byte) 0x86, 0x0D, 0x6A, (byte) 0xAE}, 2},           // 50*2 = 100
            {new byte[]{(byte) 0x10, 25, (byte) 0x86, (byte) 0x10, 4, (byte) 0x86, 0x6A, (byte) 0xAE}, 2}, // 25*4 = 100
            {new byte[]{(byte) 0x10, 100, (byte) 0x86, 0x0C, 0x0D, 0x6E, 0x62, (byte) 0xAE}, 3}, // 100 + (1/2) = 100.5
            {new byte[]{(byte) 0x10, 101, (byte) 0x86, (byte) 0xAE}, 1},                      // bipush 101; i2f; freturn
            {new byte[]{(byte) 0x10, 33, (byte) 0x86, (byte) 0x10, 3, (byte) 0x86, 0x6A, 0x0C, 0x62, (byte) 0xAE}, 2}, // 33*3+1 = 100
    };
    private static final Object[][] READ_PHASE_VARIANTS = {
            {new byte[]{(byte) 0x05, (byte) 0xAC}, 1},                                  // iconst_2; ireturn
            {new byte[]{(byte) 0x10, 2, (byte) 0xAC}, 1},                               // bipush 2; ireturn
            {new byte[]{0x04, 0x04, 0x60, (byte) 0xAC}, 2},                             // iconst_1; iconst_1; iadd; ireturn
            {new byte[]{(byte) 0x10, 7, (byte) 0x10, 5, 0x64, (byte) 0xAC}, 2},         // bipush 7; bipush 5; isub; ireturn
    };

    // 写槽多形态：伪装自然推进（相位 1、进度 99.x）再落完成值（随机进度 + 相位 2 + 随机时间偏移）
    private static final float[][] SMASH_PHASE_LEAD = {{99.6f, 1}, {99.8f, 1}, {99.9f, 1}, {99.7f, 1}};
    private static final float[] SMASH_PROGRESS = {100.0f, 100.1f, 100.25f, 100.5f, 101.0f, 100.01f, 100.75f};

    private KillAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if (args == null || args.isEmpty()) return;
        if ("restore".equalsIgnoreCase(args.trim())) {
            restoreAll(inst);
            return;
        }
        // args 格式："internalName|uuid"（新）或纯 "internalName"（兼容旧调用）
        String internalName = args;
        String gateKey = null;
        int bar = args.indexOf('|');
        if (bar > 0) {
            internalName = args.substring(0, bar);
            gateKey = args.substring(bar + 1).trim();
        }
        internalName = internalName.replace('.', '/');
        final String gate = gateKey == null || gateKey.isEmpty() ? null : gateKey;
        // 时序放行：mod 输入（高优先级）先行，本 agent（次优先级）等待游戏侧
        // 在下一 tick 通过系统属性放行后再执行（attach 与放行均完成后生效）。
        if (gate != null && !awaitGate(gate)) {
            return; // 超时未放行：放弃本次（游戏侧 REMOVAL_PUMP 会重试）
        }
        runKillChain(internalName, gate, inst);
    }

    /** 轮询系统属性放行信号：每 50ms 检查，最多 30 秒。 */
    private static boolean awaitGate(String gateKey) {
        String property = "endsustain.agent.go." + gateKey;
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            if (System.getProperty(property) != null) {
                System.clearProperty(property);
                return true;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------- 四级降级链

    private static void runKillChain(String internalName, String uuid, Instrumentation inst) {
        INST = inst;
        try {
            ClassLoader gameLoader = locateGameLoader(inst);
            if (gameLoader == null) return;
            List<Object> targets = collectTargets(inst, internalName, gameLoader, uuid);
            if (targets.isEmpty()) return;

            // 竞态设计（与 mod 侧职责分离）：
            // - mod 输入（优先，先发生）：实例状态——数据项归零/字段归零/removalReason/deathTime/Tag。
            // - agent 输入（随后，attach 完成后立即执行，不 sleep）：
            //   类字节码 retransform（JVM 安全点原子生效）+ 黑盒静态槽 + 黑盒公开 write API。
            // 两者写入域不相交 → 结构性无竞态；agent 不触碰任何目标实例字段。

            // 0) 特征定位
            Method writer = locateSlotWriter(inst);
            List<Method> readers = locateSlotReaders(inst, writer);
            List<Method> judges = locateJudges(inst);

            // 1) L1 写槽：伪装自然推进（相位 1、进度 99.x）+ 随机完成值（相位 2），
            //    值/时序多样化，避免固定 100.0f+2 特征被反针对检测
            if (writer != null) {
                java.util.Random rnd = new java.util.Random(System.nanoTime());
                for (Object target : targets) {
                    try {
                        float[] lead = SMASH_PHASE_LEAD[rnd.nextInt(SMASH_PHASE_LEAD.length)];
                        writer.invoke(null, target, lead[0], (int) lead[1],
                                System.currentTimeMillis() - rnd.nextInt(4000));
                        float progress = SMASH_PROGRESS[rnd.nextInt(SMASH_PROGRESS.length)];
                        writer.invoke(null, target, progress, 2,
                                System.currentTimeMillis() + rnd.nextInt(2000) - 1000);
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 2) L1.5 读拦截（主手段）：Java 侧读方法输出常量化
            int readPatched = interceptReads(inst, readers);

            // 3) L-c 判定器重写（读拦截不完整时的兜底，多形态字节码）
            if (readPatched < 2) {
                java.util.Random jrnd = new java.util.Random(System.nanoTime());
                for (Method judge : judges) {
                    try {
                        Object[] chosen = JUDGE_VARIANTS[jrnd.nextInt(JUDGE_VARIANTS.length)];
                        patchAndRetransform(inst, judge,
                                "(Lnet/minecraft/world/entity/LivingEntity;)Z",
                                (byte[]) chosen[0], (Integer) chosen[1]);
                    } catch (Throwable ignored) {
                    }
                }
            }

            // 3b) 虚方法判定器重写：返回 boolean 的无参实例覆写（如模组自定义的
            //     存活/死亡判定）。此类方法返回常规布尔结果，改写为恒 false 后
            //     目标被世界视为已死。
            java.util.Random vrnd = new java.util.Random(System.nanoTime());
            for (Method judge : locateVirtualJudges(inst, internalName)) {
                try {
                    Object[] chosen = JUDGE_VARIANTS[vrnd.nextInt(JUDGE_VARIANTS.length)];
                    patchAndRetransform(inst, judge, "()Z",
                            (byte[]) chosen[0], (Integer) chosen[1]);
                    System.out.println("[endsustain-agent] AGENT_VJUDGE "
                            + internalName + "#" + judge.getName());
                } catch (Throwable ignored) {
                }
            }

            // 4) 复活锚点清空
            neutralizeReviver(inst);

            // 5) L-Ω 破壳直写：dump 黑盒存储快照 + Unsafe 直写内部槽值（绕开一切字段写 guard）
            dumpAndSmashShell(inst, targets, writer);

            // 6) 硬移除三级兜底（时序保证：mod 输入已在本 tick 先行完成，
            //    agent 为次优先级输入，放行后执行；保留全部移除机制）
            for (Object target : targets) {
                hardRemove(target);
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    private static int interceptReads(Instrumentation inst, List<Method> readers) {
        if (readers == null || readers.isEmpty()) return 0;
        java.util.Random rnd = new java.util.Random(System.nanoTime());
        int patched = 0;
        for (Method reader : readers) {
            boolean isFloat = reader.getReturnType() == float.class;
            Object[][] variants = isFloat ? READ_PROGRESS_VARIANTS : READ_PHASE_VARIANTS;
            Object[] chosen = variants[rnd.nextInt(variants.length)];
            byte[] code = (byte[]) chosen[0];
            int maxStack = (Integer) chosen[1];
            String desc = isFloat
                    ? "(Lnet/minecraft/world/entity/LivingEntity;)F"
                    : "(Lnet/minecraft/world/entity/LivingEntity;)I";
            if (patchAndRetransform(inst, reader, desc, code, maxStack)) {
                patched++;
            }
        }
        return patched;
    }

    // ---------------------------------------------------------------- 特征定位（零目标符号）

    /** 槽写入器：static void write(X, float, int, long)，声明类含 native 或 Unsafe 特征。 */
    private static synchronized Method locateSlotWriter(Instrumentation inst) {
        if (SLOT_WRITER != null) return SLOT_WRITER;
        Class<?> living = cls("net.minecraft.world.entity.LivingEntity", null);
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                if (!(hasNative(c) || hasStaticUnsafe(c))) continue;
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() != void.class) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length != 4) continue;
                    if (p[1] != float.class || p[2] != int.class || p[3] != long.class) continue;
                    if (p[0] == Object.class || (living != null && p[0] == living)) {
                        m.setAccessible(true);
                        SLOT_WRITER = m;
                        return m;
                    }
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    /** 黑盒读方法：static float f(LivingEntity) 与 static int f(LivingEntity)。
     *  优先在 SLOT_WRITER 声明类内取；否则扫描含 native/Unsafe 特征且两读方法齐备的类。 */
    private static synchronized List<Method> locateSlotReaders(Instrumentation inst, Method writer) {
        if (SLOT_READERS != null) return SLOT_READERS;
        SLOT_READERS = new ArrayList<>();
        Class<?> living = cls("net.minecraft.world.entity.LivingEntity", null);
        if (writer != null) {
            Method fp = findRead(writer.getDeclaringClass(), living, float.class);
            Method ip = findRead(writer.getDeclaringClass(), living, int.class);
            if (fp != null) SLOT_READERS.add(fp);
            if (ip != null) SLOT_READERS.add(ip);
            if (SLOT_READERS.size() == 2) return SLOT_READERS;
        }
        if (living != null) {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                try {
                    if (!(hasNative(c) || hasStaticUnsafe(c))) continue;
                    Method fp = findRead(c, living, float.class);
                    Method ip = findRead(c, living, int.class);
                    if (fp == null || ip == null) continue;
                    SLOT_READERS.add(fp);
                    SLOT_READERS.add(ip);
                    break;
                } catch (Throwable ignored) {
                }
            }
        }
        return SLOT_READERS;
    }

    private static Method findRead(Class<?> c, Class<?> living, Class<?> returnType) {
        for (Method m : c.getDeclaredMethods()) {
            if (!Modifier.isStatic(m.getModifiers())) continue;
            if (m.getReturnType() != returnType) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == living) {
                m.setAccessible(true);
                return m;
            }
        }
        return null;
    }

    /** 判定器（L-c 兜底）：static boolean f(LivingEntity)，
     *  声明类同时含 static float f(LivingEntity) 读进度与 static void f(LivingEntity, float) 推进入口。 */
    private static synchronized List<Method> locateJudges(Instrumentation inst) {
        if (JUDGES != null) return JUDGES;
        JUDGES = new ArrayList<>();
        Class<?> living = cls("net.minecraft.world.entity.LivingEntity", null);
        if (living == null) return JUDGES;
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                Method j = null, prog = null, adv = null;
                for (Method m : c.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    Class<?>[] p = m.getParameterTypes();
                    if (p.length == 1 && p[0] == living) {
                        if (m.getReturnType() == boolean.class) j = m;
                        else if (m.getReturnType() == float.class) prog = m;
                    } else if (p.length == 2 && p[0] == living && p[1] == float.class
                            && m.getReturnType() == void.class) {
                        adv = m;
                    }
                }
                if (j != null && prog != null && adv != null) {
                    j.setAccessible(true);
                    JUDGES.add(j);
                }
            } catch (Throwable ignored) {
            }
        }
        return JUDGES;
    }

    /**
     * 虚方法判定器（新增规则）：返回 boolean、无参数、且由模组自身覆写的实例方法。
     *
     * <p>部分防御实体把存活/死亡判定实现在无参虚方法里（覆写原版对应方法），
     * 现有静态签名规则（{@code static boolean f(LivingEntity)}）完全匹配不到。
     * 候选过滤条件：
     * <ul>
     *   <li>实例方法、返回 {@code boolean}、无参数；</li>
     *   <li>直接声明类不在 {@code net.minecraft.} 包内（是模组自己的覆写，
     *       而非继承来的原版实现）；</li>
     *   <li>该声明类的类名与目标实体类同包或为其父类——避免把无关模组的
     *       同名方法一并改写。</li>
     * </ul>
     */
    private static synchronized List<Method> locateVirtualJudges(Instrumentation inst,
                                                                 String internalName) {
        List<Method> out = new ArrayList<>();
        try {
            Class<?> targetCls = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (internalName.equals(c.getName())) {
                    targetCls = c;
                    break;
                }
            }
            if (targetCls == null) return out;
            String targetPackage = packageOf(targetCls.getName());
            for (Class<?> cur = targetCls;
                 cur != null && LivingEntityClass().isAssignableFrom(cur);
                 cur = cur.getSuperclass()) {
                String pkg = packageOf(cur.getName());
                if (pkg.startsWith("net.minecraft.")) break;
                // 只处理目标实体自身的继承链，且包名与目标一致（同模组）
                if (!pkg.equals(targetPackage)) break;
                for (Method m : cur.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers())) continue;
                    if (m.getReturnType() != boolean.class) continue;
                    if (m.getParameterCount() != 0) continue;
                    if (m.isSynthetic() || m.isBridge()) continue;
                    m.setAccessible(true);
                    out.add(m);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        return out;
    }

    private static Class<?> LivingEntityClass() {
        Class<?> living = cls("net.minecraft.world.entity.LivingEntity", null);
        return living == null ? Object.class : living;
    }

    private static String packageOf(String className) {
        int dot = className.lastIndexOf('.');
        return dot < 0 ? "" : className.substring(0, dot) + ".";
    }

    /** 复活锚点：静态 Map 字段 + 无参静态方法名含 reassert/reanimate/revive/anchor → clear()。 */
    private static void neutralizeReviver(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            try {
                Field mapField = null;
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())
                            && Map.class.isAssignableFrom(f.getType())) {
                        mapField = f;
                        break;
                    }
                }
                if (mapField == null) continue;
                boolean hit = false;
                for (Method m : c.getDeclaredMethods()) {
                    if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0) {
                        String n = m.getName().toLowerCase();
                        if (n.contains("reassert") || n.contains("reanimate")
                                || n.contains("revive") || n.contains("anchor")) {
                            hit = true;
                            break;
                        }
                    }
                }
                if (!hit) continue;
                mapField.setAccessible(true);
                Object map = mapField.get(null);
                if (map instanceof Map<?, ?> mm) mm.clear();
            } catch (Throwable ignored) {
            }
        }
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

    // ---------------------------------------------------------------- 字节码改写（手写 patcher，零 ASM 依赖）

    /** 对方法所在类 retransform：方法体替换为指定字节码；保存原始字节码供 restore。 */
    private static boolean patchAndRetransform(Instrumentation inst, Method method,
                                               String methodDesc, byte[] newCode, int maxStack) {
        Class<?> targetClass = method.getDeclaringClass();
        final String internal = targetClass.getName();
        final String methodName = method.getName();
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name,
                                    Class<?> beingRedefined, ProtectionDomain pd,
                                    byte[] classfileBuffer) {
                if (!internal.equals(name)) return null;
                try {
                    byte[] patched = patchMethod(classfileBuffer, methodName, methodDesc, newCode, maxStack);
                    if (patched != null) {
                        ORIGINAL_BYTES.putIfAbsent(internal, classfileBuffer);
                        return patched;
                    }
                } catch (Throwable t) {
                    t.printStackTrace();
                }
                return null;
            }
        };
        inst.addTransformer(transformer, true);
        try {
            inst.retransformClasses(targetClass);
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            inst.removeTransformer(transformer);
        }
        return ORIGINAL_BYTES.containsKey(internal);
    }

    /** 还原全部已改写类的原始字节码。 */
    private static void restoreAll(Instrumentation inst) {
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name,
                                    Class<?> beingRedefined, ProtectionDomain pd,
                                    byte[] classfileBuffer) {
                byte[] original = ORIGINAL_BYTES.remove(name);
                return original;
            }
        };
        inst.addTransformer(transformer, true);
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (ORIGINAL_BYTES.containsKey(c.getName())) {
                    inst.retransformClasses(c);
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        } finally {
            inst.removeTransformer(transformer);
        }
    }

    /**
     * 最小 class 文件字节码 patcher：
     * 解析常量池与 methods 段，找到 name+desc 匹配的方法，把其 Code 属性替换为
     * 给定方法体字节码（max_stack 参数化），并整体重组输出。不触碰常量池。
     * 目标方法为 native（无 Code 属性）时返回 null（由判定层 L-c 兜底）。
     */
    private static byte[] patchMethod(byte[] b, String methodName, String methodDesc,
                                      byte[] newCode, int maxStack) throws Exception {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(b));
        int magic = in.readInt();
        if (magic != 0xCAFEBABE) return null;
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> { // Utf8
                    int len = in.readUnsignedShort();
                    byte[] s = new byte[len];
                    in.readFully(s);
                    utf8[i] = new String(s, java.nio.charset.StandardCharsets.UTF_8);
                }
                case 7, 8, 16, 19, 20 -> in.skipBytes(2);
                case 15 -> in.skipBytes(3);
                case 3, 4, 9, 10, 11, 12, 17, 18 -> in.skipBytes(4);
                case 5, 6 -> { in.skipBytes(8); i++; } // long/double 占两个槽
                default -> throw new IllegalStateException("bad cp tag " + tag + " at " + i);
            }
        }
        in.readUnsignedShort(); // access
        in.readUnsignedShort(); // this
        in.readUnsignedShort(); // super
        int ifaceCount = in.readUnsignedShort();
        for (int i = 0; i < ifaceCount; i++) in.skipBytes(2);
        int fieldCount = in.readUnsignedShort();
        for (int i = 0; i < fieldCount; i++) skipMember(in);
        int methodCount = in.readUnsignedShort();
        long methodsStart = b.length - in.available();

        long targetCodeAttr = -1;      // Code 属性 attribute_length 字段位置
        long targetAttrStart = -1;     // Code 属性 name_index 起点
        int origMaxLocals = 1;
        long targetMethodEnd = -1;
        for (int i = 0; i < methodCount; i++) {
            in.readUnsignedShort(); // access
            int mn = in.readUnsignedShort();
            int md = in.readUnsignedShort();
            int ac = in.readUnsignedShort();
            boolean match = methodName.equals(utf8[mn]) && methodDesc.equals(utf8[md]);
            for (int k = 0; k < ac; k++) {
                int an = in.readUnsignedShort();
                long alenPos = b.length - in.available();
                long alen = readU4(in);
                if (match && "Code".equals(utf8[an])) {
                    in.readUnsignedShort(); // max_stack
                    origMaxLocals = in.readUnsignedShort();
                    long codeLen = readU4(in);
                    in.skipBytes((int) codeLen);
                    int excLen = in.readUnsignedShort();
                    in.skipBytes((int) (8L * excLen));
                    int codeAttrCount = in.readUnsignedShort();
                    for (int q = 0; q < codeAttrCount; q++) {
                        in.readUnsignedShort();
                        long l = readU4(in);
                        in.skipBytes((int) l);
                    }
                    targetCodeAttr = alenPos;
                    targetAttrStart = b.length - in.available() - alen - 6L;
                } else {
                    in.skipBytes((int) alen);
                }
            }
            long mEnd = b.length - in.available();
            if (match && targetCodeAttr >= 0) {
                targetMethodEnd = mEnd;
                break;
            }
        }
        if (targetCodeAttr < 0 || targetMethodEnd < 0) return null;

        int newAttrLen = 2 + 2 + 4 + newCode.length + 2 + 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream(b.length);
        out.write(b, 0, (int) targetAttrStart);          // 到 attribute name_index 前
        out.write(b, (int) targetAttrStart, 6);          // name_index(2) + attribute_length(4) 原值位置
        byte[] partial = out.toByteArray();
        writeU4(partial, (int) (partial.length - 4), newAttrLen); // 覆盖 attribute_length
        out.reset();
        out.write(partial);
        out.write(new byte[]{0, (byte) maxStack});        // max_stack
        out.write(new byte[]{(byte) (origMaxLocals >> 8), (byte) origMaxLocals}); // max_locals
        writeU4To(out, newCode.length);                   // code_length
        out.write(newCode);                               // code
        out.write(new byte[]{0, 0});                      // exception_table_length
        out.write(new byte[]{0, 0});                      // attributes_count
        out.write(b, (int) targetMethodEnd, b.length - (int) targetMethodEnd); // 尾部
        return out.toByteArray();
    }

    private static void skipMember(DataInputStream in) throws Exception {
        in.readUnsignedShort();
        in.readUnsignedShort();
        in.readUnsignedShort();
        int ac = in.readUnsignedShort();
        for (int k = 0; k < ac; k++) {
            in.readUnsignedShort();
            long l = readU4(in);
            in.skipBytes((int) l);
        }
    }

    private static long readU4(DataInputStream in) throws Exception {
        return ((long) in.readUnsignedShort() << 16) | in.readUnsignedShort();
    }

    private static void writeU4(byte[] arr, int pos, long v) {
        arr[pos] = (byte) (v >> 24);
        arr[pos + 1] = (byte) (v >> 16);
        arr[pos + 2] = (byte) (v >> 8);
        arr[pos + 3] = (byte) v;
    }

    private static void writeU4To(ByteArrayOutputStream out, long v) {
        out.write((int) (v >> 24));
        out.write((int) (v >> 16));
        out.write((int) (v >> 8));
        out.write((int) v);
    }

    // ---------------------------------------------------------------- L-Ω 破壳直写

    /**
     * 破壳兜底：动态 dump 黑盒存储快照（额外进程 stdout 落盘，供人工破壳），
     * 并用 Unsafe 直写内部值——绕开一切 Java 字段写 guard（PUTFIELD 改写层）、
     * setter 覆写与 native 边界：
     *   - static float 字段（值域像进度槽）→ putFloat 100.0f
     *   - static int 字段（名含 phase/progress/stage 且值 ∈ [0,16]）→ putInt 2
     *   - static long 地址字段 → getFloat 探活（值 ∈ [0,1e7]）→ putFloat 100.0f
     *   - Direct ByteBuffer → 取堆外 address → 同上探活直写
     *   - 静态 Map（锚点表）→ clear
     * 写前值进入 dump 快照（失败现场可复盘）；全部 try-catch，单点失败不中断链。
     */
    private static void dumpAndSmashShell(Instrumentation inst, List<Object> targets, Method writer) {
        try {
            java.util.Random rnd = new java.util.Random(System.nanoTime());
            Object unsafe = theUnsafe();
            Method getFloat = unsafe.getClass().getMethod("getFloat", Object.class, long.class);
            Method getFloatAddr = unsafe.getClass().getMethod("getFloat", long.class);
            Method putFloat = unsafe.getClass().getMethod("putFloat", Object.class, long.class, float.class);
            Method putFloatAddr = unsafe.getClass().getMethod("putFloat", long.class, float.class);
            Method putIntObj = unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class);
            Method staticOffset = unsafe.getClass().getMethod("staticFieldOffset", Field.class);
            Method staticBase = unsafe.getClass().getMethod("staticFieldBase", Field.class);

            java.util.Set<Class<?>> seen = new java.util.HashSet<>();
            for (Object target : targets) {
                for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
                    if (!seen.add(c)) break;
                }
            }
            // 黑盒特征类一并纳入（写槽器声明类优先）
            if (writer != null) seen.add(writer.getDeclaringClass());
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (seen.size() > 24) break;
                try {
                    if (hasNative(c) || hasStaticUnsafe(c)) seen.add(c);
                } catch (Throwable ignored) {
                }
            }

            for (Class<?> c : seen) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        boolean isStatic = Modifier.isStatic(f.getModifiers());
                        Class<?> t = f.getType();
                        Object base = isStatic ? staticBase.invoke(unsafe, f) : null;
                        long offset = isStatic
                                ? (Long) staticOffset.invoke(unsafe, f)
                                : 0L;
                        if (isStatic && base == null) continue;

                        if (t == float.class) {
                            if (isStatic) {
                                float cur = (Float) getFloat.invoke(unsafe, base, offset);
                                System.out.println("[endsustain-dump] " + c.getName() + " :: "
                                        + f.getName() + " (static float) = " + cur);
                                // 多形态：随机进度值直写，避免固定 100.0f 特征
                                if (cur >= 0.0F && cur <= 1.0E7F && cur < 100.0F) {
                                    float smash = SMASH_PROGRESS[rnd.nextInt(SMASH_PROGRESS.length)];
                                    putFloat.invoke(unsafe, base, offset, smash);
                                    System.out.println("[endsustain-smash] static float -> " + smash
                                            + " @ " + c.getName() + "." + f.getName());
                                }
                            }
                        } else if (t == int.class) {
                            if (isStatic) {
                                int cur = (Integer) unsafe.getClass()
                                        .getMethod("getInt", Object.class, long.class)
                                        .invoke(unsafe, base, offset);
                                String n = f.getName().toLowerCase();
                                boolean phaseLike = n.contains("phase") || n.contains("progress")
                                        || n.contains("stage") || n.contains("state");
                                System.out.println("[endsustain-dump] " + c.getName() + " :: "
                                        + f.getName() + " (static int) = " + cur);
                                if (phaseLike && cur >= 0 && cur <= 16 && cur != 2) {
                                    putIntObj.invoke(unsafe, base, offset, 2);
                                    System.out.println("[endsustain-smash] static int -> 2 @ "
                                            + c.getName() + "." + f.getName());
                                }
                            }
                        } else if (t == long.class) {
                            if (isStatic) {
                                long addr = (Long) unsafe.getClass()
                                        .getMethod("getLong", Object.class, long.class)
                                        .invoke(unsafe, base, offset);
                                System.out.println("[endsustain-dump] " + c.getName() + " :: "
                                        + f.getName() + " (static long) = " + addr);
                                // 探活：像堆外 float 槽地址 → 多形态随机进度直写
                                if (addr > 0L && addr < Long.MAX_VALUE - 64) {
                                    try {
                                        float probe = (Float) getFloatAddr.invoke(unsafe, addr);
                                        if (probe >= 0.0F && probe <= 1.0E7F && probe < 100.0F) {
                                            float smash = SMASH_PROGRESS[rnd.nextInt(SMASH_PROGRESS.length)];
                                            putFloatAddr.invoke(unsafe, addr, smash);
                                            System.out.println("[endsustain-smash] off-heap float -> " + smash
                                                    + " @ addr=" + addr + " (" + c.getName() + "." + f.getName() + ")");
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        } else if (t == java.nio.ByteBuffer.class || t == java.nio.Buffer.class) {
                            if (isStatic) {
                                Object buf = f.get(null);
                                System.out.println("[endsustain-dump] " + c.getName() + " :: "
                                        + f.getName() + " (buffer) = " + buf);
                                if (buf != null) {
                                    try {
                                        Object direct = Class.forName("sun.nio.ch.DirectBuffer")
                                                .getMethod("address")
                                                .invoke(buf);
                                        long addr = (Long) direct;
                                        if (addr > 0L) {
                                            float probe = (Float) getFloatAddr.invoke(unsafe, addr);
                                            if (probe >= 0.0F && probe <= 1.0E7F && probe < 100.0F) {
                                                float smash = SMASH_PROGRESS[rnd.nextInt(SMASH_PROGRESS.length)];
                                                putFloatAddr.invoke(unsafe, addr, smash);
                                                System.out.println("[endsustain-smash] direct-buffer float -> " + smash
                                                        + " @ addr=" + addr + " (" + c.getName() + "." + f.getName() + ")");
                                            }
                                        }
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                        } else if (Map.class.isAssignableFrom(t)) {
                            if (isStatic) {
                                Object map = f.get(null);
                                System.out.println("[endsustain-dump] " + c.getName() + " :: "
                                        + f.getName() + " (static map) size="
                                        + (map instanceof Map<?, ?> mm ? mm.size() : -1));
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
            // 实例字段快照（第一个目标实例，供破壳分析）
            if (!targets.isEmpty()) {
                Object first = targets.get(0);
                System.out.println("[endsustain-dump] instance " + first.getClass().getName());
                for (Field f : first.getClass().getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        Class<?> t = f.getType();
                        if (t == float.class || t == int.class || t == long.class || t == double.class
                                || t == boolean.class || t == short.class || t == byte.class) {
                            System.out.println("[endsustain-dump]   field " + f.getName()
                                    + " : " + f.get(first));
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    // ---------------------------------------------------------------- 硬移除三级兜底

    /**
     * 按目标类 + UUID 收集实例（含子类）。
     *
     * <p>{@code uuid} 非空时做精确匹配——只返回该 UUID 对应的单一实体，
     * 避免一次单目标强杀波及同类全部实体。
     */
    private static List<Object> collectTargets(Instrumentation inst, String internalName,
                                               ClassLoader gameLoader, String uuid) {
        List<Object> targets = new ArrayList<>();
        try {
            Class<?> targetCls = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (internalName.equals(c.getName())) {
                    targetCls = c;
                    break;
                }
            }
            if (targetCls == null) return targets;
            Class<?> hooks = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", true, gameLoader);
            Object server = hooks.getMethod("getCurrentServer").invoke(null);
            if (server == null) return targets;
            Iterable<?> levels = (Iterable<?>) server.getClass().getMethod("getAllLevels").invoke(server);
            for (Object level : levels) {
                Iterable<?> entities = (Iterable<?>) level.getClass().getMethod("getAllEntities").invoke(level);
                for (Object entity : entities) {
                    if (!targetCls.isInstance(entity)
                            && !entity.getClass().getName().equals(internalName)) continue;
                    // UUID 精确匹配：单目标强杀不得波及其它同类实体
                    if (uuid != null) {
                        Object entityUuid = entity.getClass().getMethod("getUUID").invoke(entity);
                        if (entityUuid == null || !uuid.equals(entityUuid.toString())) continue;
                    }
                    targets.add(entity);
                    if (uuid != null) return targets;
                }
            }
        } catch (Throwable ignored) {
        }
        return targets;
    }

    /** 硬移除三级兜底：die → remove(KILLED) → 实体管理器直删 → Unsafe 直写。 */
    private static void hardRemove(Object entity) {
        try {
            ClassLoader loader = entity.getClass().getClassLoader();
            Class<?> reasonCls = Class.forName("net.minecraft.world.entity.Entity$RemovalReason", true, loader);
            Object killed = Enum.valueOf((Class<? extends Enum>) reasonCls, "KILLED");

            // 0) die（触发死亡动画与掉落；判定已放行时生效）
            try {
                Method dieM = null;
                for (Method mm : entity.getClass().getDeclaredMethods()) {
                    if (mm.getReturnType() == void.class && mm.getParameterCount() == 1
                            && !mm.getParameterTypes()[0].isPrimitive()) {
                        String n = mm.getName();
                        if (n.equals("die") || n.equals("m_6667_")) {
                            dieM = mm;
                            break;
                        }
                    }
                }
                if (dieM != null) {
                    dieM.setAccessible(true);
                    try {
                        dieM.invoke(entity, new Object[]{null});
                    } catch (Throwable ignored) {
                    }
                }
            } catch (Throwable ignored) {
            }
            if (isRemoved(entity)) return;

            // 1) 常规 remove(KILLED)（判定已放行时生效）
            Method rm = method(entity.getClass(), "m_142687_", "remove", reasonCls);
            if (rm != null) {
                try {
                    rm.invoke(entity, killed);
                } catch (Throwable ignored) {
                }
            }
            if (isRemoved(entity)) return;

            // 2) 实体管理器直删（绕开一切方法级拦截）
            try {
                Method lvM = method(entity.getClass(), "m_9236_", "level");
                if (lvM != null) {
                    Object lv = lvM.invoke(entity);
                    if (lv != null) {
                        Field emf = field(lv.getClass(), "entityManager", "f_143244_");
                        if (emf != null) {
                            Object mgr = emf.get(lv);
                            for (Method mm : mgr.getClass().getDeclaredMethods()) {
                                if (mm.getParameterCount() == 1
                                        && mm.getParameterTypes()[0].getName().contains("EntityAccess")) {
                                    mm.setAccessible(true);
                                    mm.invoke(mgr, entity);
                                    break;
                                }
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (isRemoved(entity)) return;

            // 3) Unsafe 直写字段（绕开字节码 PUTFIELD 改写层；字段名已 javap 实证）
            try {
                Object unsafe = theUnsafe();
                Method putRef = unsafe.getClass().getMethod("putObject", Object.class, long.class, Object.class);
                Method putBool = unsafe.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class);
                Method putInt = unsafe.getClass().getMethod("putInt", Object.class, long.class, int.class);
                Method objOffset = unsafe.getClass().getMethod("objectFieldOffset", Field.class);

                Field rr = field(entity.getClass(), "removalReason", "f_146795_");
                if (rr != null) putRef.invoke(unsafe, entity, objOffset.invoke(unsafe, rr), killed);
                Field dead = field(entity.getClass(), "dead", "f_20890_");
                if (dead != null) putBool.invoke(unsafe, entity, objOffset.invoke(unsafe, dead), true);
                Field dt = field(entity.getClass(), "deathTime", "f_20919_");
                if (dt != null) putInt.invoke(unsafe, entity, objOffset.invoke(unsafe, dt), 19);
            } catch (Throwable ignored) {
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean isRemoved(Object entity) {
        try {
            Method m = method(entity.getClass(), "m_213877_", "isRemoved");
            if (m != null) return (Boolean) m.invoke(entity);
        } catch (Throwable ignored) {
        }
        return false;
    }


    // ---------------------------------------------------------------- 反射基础设施

    private static Method method(Class<?> c, String a, String b, Class<?>... params) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            for (String n : new String[]{a, b}) {
                try {
                    Method m = cur.getDeclaredMethod(n, params);
                    m.setAccessible(true);
                    return m;
                } catch (NoSuchMethodException ignored) {
                }
            }
        }
        return null;
    }


    private static Field field(Class<?> c, String a, String b) {
        for (Class<?> cur = c; cur != null; cur = cur.getSuperclass()) {
            for (String n : new String[]{a, b}) {
                try {
                    Field f = cur.getDeclaredField(n);
                    f.setAccessible(true);
                    return f;
                } catch (NoSuchFieldException ignored) {
                }
            }
        }
        return null;
    }

    private static Class<?> cls(String name, ClassLoader loader) {
        try {
            if (loader != null) return Class.forName(name, true, loader);
            for (Class<?> c : INST.getAllLoadedClasses()) {
                if (name.equals(c.getName())) return c;
            }
            return Class.forName(name);
        } catch (Throwable t) {
            return null;
        }
    }


    private static ClassLoader locateGameLoader(Instrumentation inst) {
        for (Class<?> c : inst.getAllLoadedClasses()) {
            if ("net.minecraft.server.MinecraftServer".equals(c.getName())) {
                return c.getClassLoader();
            }
        }
        return null;
    }

    private static Object theUnsafe() throws Throwable {
        Class<?> uc = Class.forName("sun.misc.Unsafe");
        Field f = uc.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }
}
