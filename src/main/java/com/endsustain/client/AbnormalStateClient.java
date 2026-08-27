package com.endsustain.client;

/** 客户端全局异常状态标志：置位后由 mixin 在下一次主循环渲染 tick 触发空指针异常。 */
public final class AbnormalStateClient {
    private static volatile boolean corrupted;

    private AbnormalStateClient() {}

    public static void markCorrupted() {
        corrupted = true;
    }

    public static boolean isCorrupted() {
        return corrupted;
    }
}
