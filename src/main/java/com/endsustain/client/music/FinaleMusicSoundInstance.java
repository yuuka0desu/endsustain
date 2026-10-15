package com.endsustain.client.music;

import com.endsustain.EndSustain;
import com.endsustain.config.EndSustainConfig;
import net.minecraft.client.resources.sounds.AbstractSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.TickableSoundInstance;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;

/**
 * 落幕之终焉的战斗音乐实例。
 *
 * <h2>为什么继承 {@link AbstractSoundInstance} 而非 {@code SimpleSoundInstance}</h2>
 * 需要在 {@link #tick()} 里逐 tick 调整音量做淡入淡出，{@code SimpleSoundInstance}
 * 不提供 tick 回调。
 *
 * <h2>生命周期</h2>
 * {@code tick()} 由 {@code SoundEngine} 调用。注意 {@code SoundManager} 对
 * {@link SoundSource#MUSIC} 通道有抢占行为——播放新的 MUSIC 音源会自动停掉旧的。
 * 因此本类<b>不用于双实例重叠</b>，由 {@link FinaleMusicPlayer} 串行控制：
 * 旧实例 ``beginFadeOut()`` 淡出至 0 后停止，再构造新实例淡入。
 */
public class FinaleMusicSoundInstance extends AbstractSoundInstance implements TickableSoundInstance {

    /** 淡入淡出总时长（tick）。 */
    private final int fadeTicks;
    /** 相对音量系数 0..1，由淡入淡出驱动。 */
    private float scale;
    private boolean fadingOut;
    private boolean stopped;

    public FinaleMusicSoundInstance(SoundEvent event, int fadeTicks, boolean looping) {
        super(event, SoundSource.MUSIC, SoundInstance.createUnseededRandom());
        this.fadeTicks = Math.max(1, fadeTicks);
        this.looping = looping;
        // 音乐不做距离衰减
        this.attenuation = SoundInstance.Attenuation.NONE;
        this.relative = false;
        this.scale = 0.0F;
        this.volume = 0.0F;
        this.pitch = 1.0F;
    }

    @Override
    public void tick() {
        if (stopped) return;

        float step = 1.0F / fadeTicks;
        if (fadingOut) {
            scale = Math.max(0.0F, scale - step);
            if (scale <= 0.0F) {
                stopped = true;
                return;
            }
        } else {
            scale = Math.min(1.0F, scale + step);
        }

        this.volume = scale * (float) (double) EndSustainConfig.FINALE_MUSIC_VOLUME.get();
    }

    /** 开始淡出。淡出至 0 后 {@link #isStopped()} 返回 true。 */
    public void beginFadeOut() {
        this.fadingOut = true;
    }

    /** 立即终止（断线、退出世界等场景），不做淡出。 */
    public void stopImmediate() {
        this.scale = 0.0F;
        this.volume = 0.0F;
        this.stopped = true;
    }

    /**
     * 把音源位置同步到玩家。
     *
     * <p>{@code attenuation = NONE} 时位置不影响音量，但 {@code SoundEngine}
     * 仍会用位置做距离剔除判断——玩家跑远后声音会被误剔除，因此需要每 tick 跟随。
     */
    public void followPlayer(LocalPlayer player) {
        this.x = player.getX();
        this.y = player.getY();
        this.z = player.getZ();
    }

    @Override
    public boolean isStopped() {
        return stopped;
    }
}
