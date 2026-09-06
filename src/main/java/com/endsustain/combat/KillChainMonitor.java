package com.endsustain.combat;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 终焉强杀链独立线程监听器：
 * - 监听终焉之刃的所有伤害流程（入口/各层/看门狗/超时/agent）；
 * - 监听被强杀生物的所有 tick 事件（每 tick 状态快照）。
 * 事件经有界队列交给独立守护线程异步写入 logs/endsustain-killchain.log，
 * 不阻塞游戏主线程；队列满时丢弃最旧事件。
 */
public final class KillChainMonitor {
    private static final int QUEUE_CAPACITY = 20000;
    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static volatile boolean started;

    private KillChainMonitor() {
    }

    /** 惰性启动监听线程（守护线程，游戏退出自动结束）。 */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(() -> {
            Path logFile = Path.of("logs", "endsustain-killchain.log");
            try {
                if (!Files.exists(logFile.getParent())) {
                    Files.createDirectories(logFile.getParent());
                }
            } catch (Throwable ignored) {
            }
            try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(logFile,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true)) {
                while (started) {
                    String event = QUEUE.poll(1, TimeUnit.SECONDS);
                    if (event == null) continue;
                    writer.println(System.currentTimeMillis() + " " + event);
                }
            } catch (Throwable ignored) {
            }
        }, "endsustain-killchain-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    /** 提交一条监听事件（非阻塞，线程安全）。 */
    public static void log(String event) {
        start();
        QUEUE.offer("[" + Thread.currentThread().getName() + "] " + event);
    }

    /** 供强杀链埋点：记录目标当前完整状态快照。 */
    public static void logTargetState(String phase, net.minecraft.world.entity.LivingEntity target,
                                      String detail) {
        log(phase + " 目标=" + target.getType().getRegistryName()
                + " id=" + target.getId()
                + " hp=" + target.getHealth() + "/" + target.getMaxHealth()
                + " deathTime=" + target.deathTime
                + " dead=" + target.isDeadOrDying()
                + " removed=" + target.isRemoved()
                + (detail == null ? "" : " " + detail));
    }
}
