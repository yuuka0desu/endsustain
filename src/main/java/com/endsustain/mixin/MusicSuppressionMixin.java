package com.endsustain.mixin;

import com.endsustain.client.music.FinaleMusicPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.sounds.SoundSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 音乐通道压制：我方战斗音乐播放期间，拒绝其他 {@link SoundSource#MUSIC} 音源。
 *
 * <h2>为什么需要</h2>
 * 「多 Boss 不重音 + 优先使用我们的音乐」要求同一时刻只有一首音乐。
 * Minecraft 的 {@code SoundEngine} 虽对 MUSIC 通道有抢占行为，
 * 但抢占是<b>双向</b>的——其他模组的 Boss 音乐同样能把我们压掉，
 * 双方若各自周期性重播就会互相抢占，听感上表现为抖动。
 *
 * <p>本 Mixin 在 {@code SoundManager.play} 入口做单向拦截：
 * 只要 {@link FinaleMusicPlayer} 处于激活状态，非本模组的 MUSIC 音源直接不播。
 *
 * <h2>为什么不拦截 MUSIC 以外的音源</h2>
 * 环境音、唱片机、音效等不应被压制——只针对 MUSIC 通道。
 *
 * <h2>幂等性</h2>
 * 拦截是被动的：我方音乐未激活时本 Mixin 不产生任何行为，无副作用。
 */
@Mixin(SoundManager.class)
public abstract class MusicSuppressionMixin {

    @Inject(method = "play", at = @At("HEAD"), cancellable = true)
    private void endsustain$suppressOtherBossMusic(SoundInstance sound, CallbackInfo ci) {
        // 仅压制 MUSIC 通道
        if (sound.getSource() != SoundSource.MUSIC) return;
        // 我方音乐未激活时不干预
        if (!FinaleMusicPlayer.isActive()) return;
        // 放行我方自己的音源
        if (FinaleMusicPlayer.isOwnSound(sound)) return;
        // 其余 MUSIC 音源一律拦截
        ci.cancel();
    }
}
