package com.endsustain.combat;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 终焉标记/劫持状态的模组私有存储（审计 P2）：
 * 标记与 getHealth 劫持开关不再依赖目标可写的 persistentData 公开键——
 * 防御 Mod 每 tick remove 键不再能拆除 Mixin 拦截体系。
 * 条目携带实体 id 双匹配，堵住「同 UUID 重生/克隆 → 新实例被误锁」的残留漏洞。
 */
public final class TerminalStateRegistry {
    private static final Map<UUID, Integer> TERMINAL = new ConcurrentHashMap<>();
    private static final Map<UUID, Integer> HIJACK = new ConcurrentHashMap<>();
    private static final int PRUNE_THRESHOLD = 64;
    private static int pruneCounter;

    private TerminalStateRegistry() {
    }

    public static void mark(LivingEntity entity) {
        TERMINAL.put(entity.getUUID(), entity.getId());
    }

    public static void hijack(LivingEntity entity) {
        HIJACK.put(entity.getUUID(), entity.getId());
    }

    public static void unmark(UUID id) {
        TERMINAL.remove(id);
        HIJACK.remove(id);
    }

    /** UUID + 实体 id 双匹配：同 UUID 重生的新实例（id 不同）不被误锁。 */
    public static boolean isMarked(Entity entity) {
        return TERMINAL.getOrDefault(entity.getUUID(), -1) == entity.getId();
    }

    public static boolean isHijacked(Entity entity) {
        return HIJACK.getOrDefault(entity.getUUID(), -1) == entity.getId();
    }

    public static void clear() {
        TERMINAL.clear();
        HIJACK.clear();
    }

    /**
     * 挂在 ServerTickEvent 入口：定时（每 200 tick）+ 阈值（>64 条）双触发，
     * 用 server.getAllLevels() 反向核对 UUID，清理已消失实体的 stale 条目。
     * ConcurrentHashMap 弱一致迭代天然安全。
     */
    public static void prune(MinecraftServer server) {
        boolean due = TERMINAL.size() > PRUNE_THRESHOLD || ++pruneCounter % 200 == 0;
        if (!due) return;
        pruneCounter = 0;
        for (UUID id : TERMINAL.keySet()) {
            boolean alive = false;
            for (var level : server.getAllLevels()) {
                if (level.getEntities().get(id) != null) {
                    alive = true;
                    break;
                }
            }
            if (!alive) unmark(id);
        }
    }
}
