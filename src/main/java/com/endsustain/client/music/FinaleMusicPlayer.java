package com.endsustain.client.music;

import com.endsustain.EndSustain;
import com.endsustain.config.EndSustainConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 落幕之终焉战斗音乐的客户端播放器。
 *
 * <h2>职责</h2>
 * 接收服务端下发的音轨序号，管理与切换 {@link FinaleMusicSoundInstance}。
 * <b>不做阶段判定</b>——那由服务端的 {@code FinaleMusicState} 负责，
 * 保证所有玩家听到同一曲目、同一时刻。
 *
 * <h2>切换流程</h2>
 * <pre>
 * 收到新音轨
 *   → 旧实例 beginFadeOut()（20 tick 淡出）
 *   → 淡出完成后停止旧实例
 *   → 构造新实例并播放（fadeTicks 淡入）
 * </pre>
 * 单实例串行，不重叠——Minecraft 的 MUSIC 通道本身有抢占行为，重叠无意义。
 *
 * <h2>兜底</h2>
 * 维持 {@link #HEARTBEAT_TIMEOUT} 心跳超时：服务端音乐包丢失或玩家脱离服务器时，
 * 自动停止播放，避免音乐无限延续。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class FinaleMusicPlayer {

    /** 心跳超时（tick）：约 10 秒未收到服务端同步则停止。 */
    private static final int HEARTBEAT_TIMEOUT = 200;
    /** 淡出时长（tick）：固定较短，保证切换干脆。 */
    private static final int FADE_OUT_TICKS = 20;

    /** 音轨 → 声音事件注册名。与 sounds.json 的条目一一对应。 */
    private static final ResourceLocation[] TRACK_SOUNDS = {
            null,                                                                    // IDLE：无音乐
            new ResourceLocation(EndSustain.MOD_ID, "music.finale.encounter"),
            new ResourceLocation(EndSustain.MOD_ID, "music.finale.battle"),
            new ResourceLocation(EndSustain.MOD_ID, "music.finale.ultimate"),
            new ResourceLocation(EndSustain.MOD_ID, "music.finale.death_tail")
    };

    /** 需要循环播放的音轨（仅战斗曲）。 */
    private static final boolean[] TRACK_LOOPING = {
            false, false, true, false, false
    };

    /** 声音事件存在性缓存：避免每 tick 重复查询注册表。 */
    private static final Map<ResourceLocation, Boolean> SOUND_PRESENCE = new ConcurrentHashMap<>();

    private static FinaleMusicSoundInstance instance;
    /** 服务端下发的目标音轨（尚未开始播放）。 */
    private static int targetOrdinal;
    /** 当前正在播放的音轨。 */
    private static int activeOrdinal;
    private static int heartbeatTicks;
    private static boolean fadingOut;

    private FinaleMusicPlayer() {}

    /**
     * 我方音乐当前是否处于激活状态。
     *
     * <p>供音乐压制 Mixin 判断是否拦截其他 MUSIC 音源。
     * 只要有一个音轨在播（或正在淡入），即视为激活。
     */
    public static boolean isActive() {
        return instance != null || targetOrdinal != 0;
    }

    /**
     * 判断某个音源是否属于本模组的音乐。
     *
     * <p>音乐压制 Mixin 用它放行我方音源，避免自我拦截。
     */
    public static boolean isOwnSound(net.minecraft.client.resources.sounds.SoundInstance sound) {
        if (instance != null && sound == instance) return true;
        return sound.getLocation() != null
                && EndSustain.MOD_ID.equals(sound.getLocation().getNamespace());
    }

    /**
     * 接收服务端下发的音轨。
     *
     * <p>幂等：与当前目标相同时只续期心跳，不触发重播。
     */
    public static void receive(int ordinal) {
        if (ordinal < 0 || ordinal >= TRACK_SOUNDS.length) return;
        heartbeatTicks = HEARTBEAT_TIMEOUT;
        if (ordinal == targetOrdinal && ordinal == activeOrdinal) return;
        if (ordinal == targetOrdinal) return;

        targetOrdinal = ordinal;
        if (instance != null) {
            fadingOut = true; // 先淡出旧曲，淡出完成后再切
        }
    }

    /** 立即停止（断线、退出世界、换维度）。 */
    public static void stopImmediate() {
        if (instance != null) {
            instance.stopImmediate();
            instance = null;
        }
        activeOrdinal = 0;
        targetOrdinal = 0;
        fadingOut = false;
        heartbeatTicks = 0;
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;

        if (player == null || minecraft.level == null || minecraft.getSoundManager() == null) {
            stopImmediate();
            return;
        }

        // 心跳超时兜底：服务端不再同步时自动停止
        if (heartbeatTicks > 0) {
            heartbeatTicks--;
        } else if (instance != null || activeOrdinal != 0) {
            stopImmediate();
            return;
        }

        // 淡出阶段：等待音量降到 0 后停止旧实例
        if (fadingOut) {
            if (instance != null) instance.followPlayer(player);
            if (instance == null || instance.isStopped()) {
                if (instance != null) minecraft.getSoundManager().stop(instance);
                instance = null;
                fadingOut = false;
                activeOrdinal = 0;
            } else {
                return; // 淡出未完成，本 tick 不启动新曲
            }
        }

        // 启动新曲
        if (instance == null && targetOrdinal != 0) {
            FinaleMusicSoundInstance created = create(targetOrdinal, player);
            if (created == null) {
                // 音频资源缺失：静默降级，不再重试同一音轨
                activeOrdinal = targetOrdinal;
                targetOrdinal = 0;
                return;
            }
            instance = created;
            activeOrdinal = targetOrdinal;
            minecraft.getSoundManager().play(instance);
        }

        if (instance != null) instance.followPlayer(player);
    }

    /** 构造音轨实例。音频资源不存在时返回 null（静默降级）。 */
    private static FinaleMusicSoundInstance create(int ordinal, LocalPlayer player) {
        if (ordinal <= 0 || ordinal >= TRACK_SOUNDS.length) return null;
        ResourceLocation soundId = TRACK_SOUNDS[ordinal];
        if (soundId == null || !soundAvailable(soundId)) return null;

        SoundEvent soundEvent = ForgeRegistries.SOUND_EVENTS.getValue(soundId);
        if (soundEvent == null) return null;

        int fadeTicks = EndSustainConfig.FINALE_MUSIC_FADE_TICKS.get();
        FinaleMusicSoundInstance created =
                new FinaleMusicSoundInstance(soundEvent, Math.max(1, fadeTicks), TRACK_LOOPING[ordinal]);
        created.followPlayer(player);
        return created;
    }

    /**
     * 声音事件存在性检查。
     *
     * <p>音频文件未制作时，播放会静默失败且可能在日志留下警告。
     * 这里预先查询一次并缓存，避免每 tick 重复产生日志。
     */
    private static boolean soundAvailable(ResourceLocation id) {
        return SOUND_PRESENCE.computeIfAbsent(id, key -> {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.getSoundManager() == null) return Boolean.FALSE;
            boolean present = minecraft.getSoundManager().getAvailableSounds().contains(key);
            if (!present) {
                EndSustain.LOGGER.debug("[终焉音乐] 音频资源缺失，跳过播放：{}", key);
            }
            return present;
        });
    }
}
