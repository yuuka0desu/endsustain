package com.endsustain.client;

import java.lang.reflect.Field;

/**
 * 客户端全局异常状态标志：置位后由 mixin 在下一次主循环渲染 tick 触发空指针异常。
 *
 * 标志通过 Unsafe 直接写入 final 静态字段的内存偏移：
 * 对方反射 setAccessible 只能操作 Java 对象字段视图，无法轻易改写 Unsafe 直写的内存，
 * 即便被改回 false，后续 Unsafe 写入仍可再次置位。
 * Unsafe 不可用时回退为普通 volatile 字段。
 */
public final class AbnormalStateClient {
    private static final Object UNSAFE;
    private static final long CORRUPTED_OFFSET;
    private static final Object CORRUPTED_BASE;

    /** final 字段：反射修改视图被 JVM 限制，真实写入由 Unsafe 完成。 */
    private static final boolean corrupted = false;

    /** Unsafe 不可用时的回退标志。 */
    private static volatile boolean corruptedFallback;

    static {
        Object unsafe = null;
        long offset = -1L;
        Object base = null;
        try {
            Field field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            Object instance = field.get(null);
            if (instance != null) {
                unsafe = instance;
                Field target = AbnormalStateClient.class.getDeclaredField("corrupted");
                base = instance.getClass().getMethod("staticFieldBase", Field.class).invoke(instance, target);
                offset = (Long) instance.getClass().getMethod("staticFieldOffset", Field.class).invoke(instance, target);
            }
        } catch (Throwable ignored) {
        }
        UNSAFE = unsafe;
        CORRUPTED_BASE = base;
        CORRUPTED_OFFSET = offset;
    }

    private AbnormalStateClient() {}

    public static void markCorrupted() {
        if (UNSAFE != null && CORRUPTED_OFFSET >= 0L) {
            try {
                UNSAFE.getClass().getMethod("putBoolean", Object.class, long.class, boolean.class)
                        .invoke(UNSAFE, CORRUPTED_BASE, CORRUPTED_OFFSET, true);
                return;
            } catch (Throwable ignored) {
            }
        }
        corruptedFallback = true;
    }

    public static boolean isCorrupted() {
        if (UNSAFE != null && CORRUPTED_OFFSET >= 0L) {
            try {
                return (Boolean) UNSAFE.getClass().getMethod("getBoolean", Object.class, long.class)
                        .invoke(UNSAFE, CORRUPTED_BASE, CORRUPTED_OFFSET);
            } catch (Throwable ignored) {
            }
        }
        return corruptedFallback;
    }
}
