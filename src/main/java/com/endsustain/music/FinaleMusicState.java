package com.endsustain.music;

import com.endsustain.EndSustain;
import com.endsustain.config.EndSustainConfig;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.network.EndSustainNetwork;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 落幕之终焉战斗背景音乐的状态机（服务端权威）。
 *
 * <h2>生命周期语义</h2>
 * <ul>
 *   <li><b>开始</b>：Boss 实体<b>加入世界</b>即刻开始（{@link EntityJoinLevelEvent} 触发即时评估，
 *       不等轮询周期），范围内玩家立即收到音轨。</li>
 *   <li><b>结束</b>：Boss 实体<b>彻底消失</b>（移除 / 死亡 / 卸载）时停止，
 *       评估发现无存活 Boss 即下发 IDLE。</li>
 * </ul>
 *
 * <h2>范围</h2>
 * 使用 {@link EndSustainConfig#FINALE_MUSIC_RADIUS} 配置值（格）。
 * 与 Boss 血条追踪范围是两套独立机制——血条走 {@code ServerBossEvent} 默认追踪距离，
 * 音乐走本配置。二者不共用回调，避免「血条可见但听不到音乐」的错配。
 *
 * <h2>多 Boss 不重音</h2>
 * 服务端只维护<b>一个</b>全局 {@link #current} 轨道，取所有 Boss 中的最高优先阶段。
 * 客户端另有 MUSIC 通道拦截（见 {@code FinaleMusicSoundInstance} 与音乐压制 Mixin），
 * 双重保证同一时刻只有一首。
 *
 * <h2>受众跟踪</h2>
 * 记录每个玩家当前已下发的音轨序号（{@link #deliveredTo}），
 * 玩家进入范围时补发、离开范围时下发 IDLE。避免每 tick 重复下发导致客户端重播。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FinaleMusicState {

    /** 常规评估间隔（tick）。实体进出由 {@link EntityJoinLevelEvent} 与逐 tick 受众检查补充。 */
    private static final int EVAL_INTERVAL = 20;

    /**
     * 音轨枚举。<b>声明顺序即优先级</b>——{@code ordinal()} 越大优先级越高。
     * 多个 Boss 同时存在时取全局最高优先级命中者，保证只播一首。
     */
    public enum Track {
        /** 无音乐（无存活 Boss，或玩家不在范围内）。 */
        IDLE,
        /** Boss 存在且在范围内：压迫感铺垫。 */
        ENCOUNTER,
        /** 敌对期：主战斗曲。 */
        BATTLE,
        /** 终结技释放中：高潮段。 */
        ULTIMATE,
        /** 死亡尾段：终曲。 */
        DEATH_TAIL
    }

    private static Track current = Track.IDLE;
    /** 玩家 UUID → 已下发的音轨序号。用于受众进出范围时的差异下发。 */
    private static final Map<UUID, Integer> deliveredTo = new HashMap<>();

    private FinaleMusicState() {}

    /** 服务器停止时复位，防止跨存档残留。 */
    public static void reset() {
        current = Track.IDLE;
        deliveredTo.clear();
    }

    public static Track current() {
        return current;
    }

    /**
     * Boss 加入世界：立即评估，不等待轮询周期。
     *
     * <p>这是「生成后立刻开始播放」的实现点。若只依赖 {@link #EVAL_INTERVAL} 轮询，
     * 最坏会有 1 秒延迟。
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof FinaleEndsustainEntity)) return;
        if (!EndSustainConfig.FINALE_MUSIC_ENABLED.get()) return;
        evaluate(event.getLevel().getServer());
    }

    /** 服务器停止：清空受众表。 */
    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset();
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        MinecraftServer server = event.getServer();

        if (!EndSustainConfig.FINALE_MUSIC_ENABLED.get()) {
            if (current != Track.IDLE) {
                current = Track.IDLE;
                deliveredTo.clear();
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    EndSustainNetwork.sendMusicTrack(player, Track.IDLE.ordinal());
                }
            }
            return;
        }

        evaluate(server);
    }

    /**
     * 向单个玩家同步当前音轨（登录 / 重生 / 换维度时调用）。
     *
     * <p>中途加入的玩家会收到<b>当前阶段</b>的曲目，但播放从 0 秒开始——
     * 声音系统没有 seek 能力，无法对齐进度。
     */
    public static void syncTo(ServerPlayer player) {
        if (!EndSustainConfig.FINALE_MUSIC_ENABLED.get()) return;
        int ordinal = current.ordinal();
        deliveredTo.put(player.getUUID(), ordinal);
        EndSustainNetwork.sendMusicTrack(player, ordinal);
    }

    /**
     * 全局评估：遍历所有维度的 Boss，取最高优先级阶段，
     * 收集范围内玩家，做差异下发。
     *
     * <p>两条硬约束：
     * <ul>
     *   <li><b>同维度</b>：玩家只接收与自己<b>同一维度</b>的 Boss 音乐。
     *       跨维度时即使坐标距离很近也不会被计入受众。</li>
     *   <li><b>区块加载</b>：Boss 所在区块未加载时不播放——
     *       此时 Boss 实体本身不应参与评估，避免"虚空里传来音乐"。</li>
     * </ul>
     */
    private static void evaluate(MinecraftServer server) {
        if (server == null) return;

        Track best = Track.IDLE;
        Set<ServerPlayer> audience = new HashSet<>();

        for (ServerLevel level : server.getAllLevels()) {
            for (FinaleEndsustainEntity boss : level.getEntitiesOfClass(
                    FinaleEndsustainEntity.class,
                    net.minecraft.world.phys.AABB.ofSize(
                            new net.minecraft.world.phys.Vec3(0, 0, 0), 6.0E7D, 6.0E7D, 6.0E7D),
                    // 存活 + 未移除 + 所在区块已加载
                    entity -> entity.isAlive()
                            && !entity.isRemoved()
                            && level.hasChunkAt(entity.blockPosition()))) {
                Track track = trackFor(boss);
                if (track.ordinal() > best.ordinal()) best = track;
                collectAudience(level, boss, audience);
            }
        }

        boolean trackChanged = best != current;
        if (trackChanged) {
            EndSustain.LOGGER.debug("[终焉音乐] 音轨切换：{} → {}", current, best);
            current = best;
        }

        // 差异下发：状态变化时全员重发；否则只处理受众进出
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            Integer sent = deliveredTo.get(id);
            boolean inRange = audience.contains(player);

            if (!inRange) {
                if (sent != null && sent != Track.IDLE.ordinal()) {
                    deliveredTo.put(id, Track.IDLE.ordinal());
                    EndSustainNetwork.sendMusicTrack(player, Track.IDLE.ordinal());
                } else if (sent == null) {
                    deliveredTo.put(id, Track.IDLE.ordinal());
                }
                continue;
            }

            if (trackChanged || sent == null || sent != best.ordinal()) {
                deliveredTo.put(id, best.ordinal());
                EndSustainNetwork.sendMusicTrack(player, best.ordinal());
            }
        }

        // 清理已离线玩家的记录
        deliveredTo.keySet().removeIf(id -> server.getPlayerList().getPlayer(id) == null);
    }

    /** 判定单个 Boss 的阶段。优先级由枚举声明顺序保证。 */
    private static Track trackFor(FinaleEndsustainEntity boss) {
        if (boss.isCombatDeathTailStarted()) return Track.DEATH_TAIL;
        // 终结技区间：STATE_ULT_RAISE..STATE_ULT_SHEATHE（见 FinaleEndsustainEntity 的攻击状态机）
        int state = boss.getAttackState();
        if (state >= FinaleEndsustainEntity.STATE_ULT_RAISE
                && state <= FinaleEndsustainEntity.STATE_ULT_SHEATHE) {
            return Track.ULTIMATE;
        }
        if (boss.getSocialPhase() == FinaleEndsustainEntity.PHASE_HOSTILE) return Track.BATTLE;
        return Track.ENCOUNTER;
    }

    /**
     * 收集某个 Boss 音乐范围内的玩家。
     *
     * <p><b>只遍历 Boss 所在维度的玩家</b>——{@code level.players()} 天然限定同维度，
     * 跨维度玩家即使坐标接近也不会被计入。
     */
    private static void collectAudience(ServerLevel level, FinaleEndsustainEntity boss,
                                        Set<ServerPlayer> audience) {
        double radius = EndSustainConfig.FINALE_MUSIC_RADIUS.get();
        double radiusSqr = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(boss) <= radiusSqr) audience.add(player);
        }
    }
}
