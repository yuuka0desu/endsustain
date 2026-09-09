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
    /** 审计 6.8-1：单文件大小上限 4MB，滚动保留 1 份旧文件，防磁盘打满。 */
    private static final long MAX_LOG_SIZE = 4L * 1024 * 1024;
    private static final Path LOG_FILE = Path.of("logs", "endsustain-killchain.log");
    private static final Path LOG_FILE_OLD = Path.of("logs", "endsustain-killchain.log.1");
    private static final BlockingQueue<String> QUEUE = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private static volatile boolean started;

    private KillChainMonitor() {
    }

    /** 惰性启动监听线程（守护线程，游戏退出自动结束）。 */
    public static synchronized void start() {
        if (started) return;
        started = true;
        Thread thread = new Thread(() -> {
            try {
                if (!Files.exists(LOG_FILE.getParent())) {
                    Files.createDirectories(LOG_FILE.getParent());
                }
            } catch (Throwable ignored) {
            }
            PrintWriter writer = openWriter();
            long written = 0L;
            try {
                while (started) {
                    String event = QUEUE.poll(1, TimeUnit.SECONDS);
                    if (event == null) continue;
                    String line = System.currentTimeMillis() + " " + event;
                    writer.println(line);
                    written += line.length() + 2;
                    if (written >= MAX_LOG_SIZE) {
                        writer.flush();
                        writer.close();
                        rotate();
                        writer = openWriter();
                        written = 0L;
                    }
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    writer.close();
                } catch (Throwable ignored) {
                }
            }
        }, "endsustain-killchain-monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private static PrintWriter openWriter() {
        try {
            return new PrintWriter(Files.newBufferedWriter(LOG_FILE,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND), true);
        } catch (Throwable t) {
            return new PrintWriter(System.out, true);
        }
    }

    private static void rotate() {
        try {
            Files.deleteIfExists(LOG_FILE_OLD);
            Files.move(LOG_FILE, LOG_FILE_OLD);
        } catch (Throwable ignored) {
        }
    }

    /** 提交一条监听事件（非阻塞，线程安全）。 */
    public static void log(String event) {
        start();
        QUEUE.offer("[" + Thread.currentThread().getName() + "] " + event);
    }

    /** 供强杀链埋点：记录目标当前完整状态快照。 */
    public static void logTargetState(String phase, net.minecraft.world.entity.LivingEntity target,
                                      String detail) {
        log(phase + " 目标=" + typeName(target)
                + " id=" + target.getId()
                + " hp=" + target.getHealth() + "/" + target.getMaxHealth()
                + " deathTime=" + target.deathTime
                + " dead=" + target.isDeadOrDying()
                + " removed=" + target.isRemoved()
                + (detail == null ? "" : " " + detail));
    }

    /** 实体类型注册名（无注册名时回退类名）。 */
    public static String typeName(net.minecraft.world.entity.LivingEntity target) {
        net.minecraft.resources.ResourceLocation key =
                net.minecraftforge.registries.ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        return key != null ? key.toString() : target.getClass().getName();
    }
}
