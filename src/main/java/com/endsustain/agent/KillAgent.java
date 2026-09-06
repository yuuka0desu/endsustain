package com.endsustain.agent;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.ProtectionDomain;

/**
 * 外部 agent 保险层（强杀链最后兜底）。
 * 由独立进程通过 Attach API 加载进游戏 JVM：
 * 1. 先尝试经游戏公共 API 反射直接移除目标实体实例；
 * 2. 若目标类拒绝移除，则 retransform 其 tick/die 字节码，
 *    在方法开头注入「remove(KILLED); return」，下一 tick 强制移除。
 * 字节码改写经游戏类加载器内的 ASM 完成（反射调用），零编译期依赖。
 */
public final class KillAgent {

    private KillAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        agentmain(args, inst);
    }

    public static void agentmain(String args, Instrumentation inst) {
        if (args == null || args.isEmpty()) return;
        String internalName = args.replace('.', '/');
        tryReflectiveRemove(internalName, inst);
        tryRewriteClass(internalName, inst);
    }

    /** 层 1：反射经游戏公共 API 直接移除目标实体实例。 */
    private static void tryReflectiveRemove(String internalName, Instrumentation inst) {
        try {
            ClassLoader gameLoader = null;
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if ("net.minecraft.server.MinecraftServer".equals(c.getName())) {
                    gameLoader = c.getClassLoader();
                    break;
                }
            }
            if (gameLoader == null) return;
            Class<?> hooks = Class.forName("net.minecraftforge.server.ServerLifecycleHooks", true, gameLoader);
            Object server = hooks.getMethod("getCurrentServer").invoke(null);
            if (server == null) return;
            Iterable<?> levels = (Iterable<?>) server.getClass().getMethod("getAllLevels").invoke(server);
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity", true, gameLoader);
            Class<?> reasonClass = Class.forName("net.minecraft.world.entity.Entity$RemovalReason", true, gameLoader);
            Object killed = Enum.valueOf((Class<? extends Enum>) reasonClass, "KILLED");
            Method remove = entityClass.getMethod("remove", reasonClass);
            for (Object level : levels) {
                Iterable<?> entities = (Iterable<?>) level.getClass().getMethod("getAllEntities").invoke(level);
                for (Object entity : entities) {
                    if (entity.getClass().getName().equals(internalName)) {
                        remove.invoke(entity, killed);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /** 层 2：retransform 目标类，向 tick/die 方法开头注入强制移除指令。 */
    private static void tryRewriteClass(final String internalName, Instrumentation inst) {
        ClassFileTransformer transformer = new ClassFileTransformer() {
            @Override
            public byte[] transform(Module module, ClassLoader loader, String name,
                                    Class<?> beingRedefined, ProtectionDomain pd,
                                    byte[] classfileBuffer) {
                if (!internalName.equals(name)) return null;
                try {
                    return rewriteWithAsm(loader, classfileBuffer);
                } catch (Throwable t) {
                    t.printStackTrace();
                    return null;
                }
            }
        };
        inst.addTransformer(transformer, true);
        try {
            for (Class<?> c : inst.getAllLoadedClasses()) {
                if (internalName.equals(c.getName())) {
                    inst.retransformClasses(c);
                    break;
                }
            }
        } catch (Throwable t) {
            t.printStackTrace();
        }
        inst.removeTransformer(transformer);
    }

    /** 通过游戏类加载器内的 ASM 改写字节码（反射调用，无编译期依赖）。 */
    private static byte[] rewriteWithAsm(ClassLoader loader, byte[] bytes) throws Throwable {
        Class<?> crClass = Class.forName("org.objectweb.asm.ClassReader", true, loader);
        Class<?> cwClass = Class.forName("org.objectweb.asm.ClassWriter", true, loader);
        Class<?> cvClass = Class.forName("org.objectweb.asm.ClassVisitor", true, loader);
        Class<?> mvClass = Class.forName("org.objectweb.asm.MethodVisitor", true, loader);
        Class<?> opcodesClass = Class.forName("org.objectweb.asm.Opcodes", true, loader);
        int asmVersion = (Integer) opcodesClass.getField("ASM9").get(null);

        Object reader = crClass.getConstructor(byte[].class).newInstance(bytes);
        Object writer = cwClass.getConstructor(int.class).newInstance(asmVersion);

        // ClassVisitor 代理：拦截 visitMethod，包装返回注入用的 MethodVisitor 代理
        Object cvProxy = Proxy.newProxyInstance(loader, new Class<?>[]{cvClass},
                (proxy, method, args) -> {
                    if ("visitMethod".equals(method.getName())
                            && args != null && args.length >= 4 && args[1] instanceof String name
                            && ("m_8119_".equals(name) || "m_6667_".equals(name))) {
                        Object realMv = method.invoke(writer, args);
                        if (realMv == null) return null;
                        return makeInjectedMethodVisitor(loader, mvClass, realMv);
                    }
                    return method.invoke(writer, args);
                });

        Method accept = crClass.getMethod("accept", cvClass, int.class);
        accept.invoke(reader, cvProxy, 0);
        Method toByteArray = cwClass.getMethod("toByteArray");
        return (byte[]) toByteArray.invoke(writer);
    }

    /** MethodVisitor 代理：visitCode 时注入 remove(KILLED)+RETURN，其余原样委托。 */
    private static Object makeInjectedMethodVisitor(ClassLoader loader, Class<?> mvClass,
                                                    Object realMv) {
        InvocationHandler handler = (proxy, method, args) -> {
            if ("visitCode".equals(method.getName())) {
                injectKillSequence(mvClass, realMv);
            }
            return method.invoke(realMv, args);
        };
        return Proxy.newProxyInstance(loader, new Class<?>[]{mvClass}, handler);
    }

    private static void injectKillSequence(Class<?> mvClass, Object realMv) throws Throwable {
        Method visitVarInsn = mvClass.getMethod("visitVarInsn", int.class, int.class);
        Method visitFieldInsn = mvClass.getMethod("visitFieldInsn", int.class, String.class,
                String.class, String.class);
        Method visitMethodInsn = mvClass.getMethod("visitMethodInsn", int.class, String.class,
                String.class, String.class, boolean.class);
        Method visitInsn = mvClass.getMethod("visitInsn", int.class);
        // ALOAD 0
        visitVarInsn.invoke(realMv, 25, 0);
        // GETSTATIC Entity$RemovalReason.KILLED
        visitFieldInsn.invoke(realMv, 178,
                "net/minecraft/world/entity/Entity$RemovalReason", "KILLED",
                "Lnet/minecraft/world/entity/Entity$RemovalReason;");
        // INVOKEVIRTUAL Entity.remove(RemovalReason)V
        visitMethodInsn.invoke(realMv, 182,
                "net/minecraft/world/entity/Entity", "m_142687_",
                "(Lnet/minecraft/world/entity/Entity$RemovalReason;)V", false);
        // RETURN
        visitInsn.invoke(realMv, 177);
    }
}
