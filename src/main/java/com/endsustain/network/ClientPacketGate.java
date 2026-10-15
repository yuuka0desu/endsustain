package com.endsustain.network;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;

/**
 * 客户端包处理网关。
 *
 * <p>公共网络层不得直接引用 {@code com.endsustain.client.*}——专用服务器在类解析阶段
 * 会因缺少客户端类而失败。本类位于公共 {@code network} 包内，且<b>不 import 任何客户端类型</b>，
 * 通过 {@link DistExecutor#unsafeRunWhenOn} 把客户端调用延迟到运行时解析：
 * 专用服务器上 lambda 体永不执行，客户端类也不会被加载。
 *
 * <p>用法约定：所有 PLAY_TO_CLIENT 包的 handler 只调本类的静态方法，
 * 不直接触碰 {@code client.*}。
 */
public final class ClientPacketGate {

    private ClientPacketGate() {}

    /**
     * 音乐轨切换下发。
     *
     * <p>注意此处以字符串形式引用客户端类——编译期不产生静态链接，
     * 专用服务器不会因本方法而加载 {@code FinaleMusicPlayer}。
     */
    public static void musicTrack(int trackOrdinal) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.endsustain.client.music.FinaleMusicPlayer.receive(trackOrdinal));
    }
}
