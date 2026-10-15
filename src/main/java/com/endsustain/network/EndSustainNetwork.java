package com.endsustain.network;

import com.endsustain.EndSustain;
import com.endsustain.client.FinalePresenceEffects;
import com.endsustain.client.FinaleWeatherEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

public final class EndSustainNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(EndSustain.MOD_ID, "main"), () -> VERSION, VERSION::equals, VERSION::equals);

    private EndSustainNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, EnvironmentPacket.class, EnvironmentPacket::encode,
                EnvironmentPacket::decode, EnvironmentPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, FinalePathRequestPacket.class, FinalePathRequestPacket::encode,
                FinalePathRequestPacket::decode, FinalePathRequestPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(2, FinalePathDataPacket.class, FinalePathDataPacket::encode,
                FinalePathDataPacket::decode, FinalePathDataPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(3, FinaleSkillTriggerPacket.class, FinaleSkillTriggerPacket::encode,
                FinaleSkillTriggerPacket::decode, FinaleSkillTriggerPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(4, TidalTentacleTogglePacket.class, TidalTentacleTogglePacket::encode,
                TidalTentacleTogglePacket::decode, TidalTentacleTogglePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_SERVER));
        CHANNEL.registerMessage(5, ThornSpikePacket.class, ThornSpikePacket::encode,
                ThornSpikePacket::decode, ThornSpikePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(6, ScreenTearPacket.class, ScreenTearPacket::encode,
                ScreenTearPacket::decode, ScreenTearPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(7, NullCrashPacket.class, NullCrashPacket::encode,
                NullCrashPacket::decode, NullCrashPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(8, AbnormalStatePacket.class, AbnormalStatePacket::encode,
                AbnormalStatePacket::decode, AbnormalStatePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(9, NullPulsePacket.class, NullPulsePacket::encode,
                NullPulsePacket::decode, NullPulsePacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(10, UltimateFilterPacket.class, UltimateFilterPacket::encode,
                UltimateFilterPacket::decode, UltimateFilterPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(11, MusicTrackPacket.class, MusicTrackPacket::encode,
                MusicTrackPacket::decode, MusicTrackPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    public static void broadcastEnvironment(MinecraftServer server, boolean active) {
        CHANNEL.send(PacketDistributor.ALL.noArg(), new EnvironmentPacket(active));
    }

    public static void sendEnvironment(ServerPlayer player, boolean active) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new EnvironmentPacket(active));
    }

    public static void requestFinalePath() { CHANNEL.sendToServer(new FinalePathRequestPacket()); }
    public static void triggerFinaleSkill(int skill) { CHANNEL.sendToServer(new FinaleSkillTriggerPacket(skill)); }
    public static void toggleTidalTentacles() { CHANNEL.sendToServer(new TidalTentacleTogglePacket()); }

    public static void sendScreenTear(ServerLevel level, net.minecraft.world.phys.Vec3 center,
                                      double radius, int durationTicks) {
        CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                center.x, center.y, center.z, radius, level.dimension())),
                new ScreenTearPacket(durationTicks));
    }

    public static void sendNullCrash(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new NullCrashPacket());
    }

    /**
     * 湮灭打击（统一入口，仅 NPE 通道，服务器主机安全）：
     * 时序：标记 → 刷新 → 断线兜底，全部为轻量包，不对服务器造成任何负载。
     * 1) 异常状态标记（客户端渲染帧 runTick NPE → Minecraft.crash() → CrashReport
     *    「Game crashed」进程退出——主循环的 catch 会把 NPE 转为崩溃报告而非断线）；
     * 2) 空包刷新 Netty 队列，压缩拦截窗口；
     * 3) 断线包最后发（兜底：若标记通道被拦截，至少断开连接）。
     * 顺序保证：崩溃信号先于断线送达，不被连接关闭丢包。
     */
    public static void annihilate(ServerPlayer target) {
        try {
            sendAbnormalState(target);
            sendNullPulse(target);
        } catch (Throwable ignored) {
        }
        try {
            sendNullCrash(target);
        } catch (Throwable ignored) {
        }
    }

    /** 对施放者渲染范围内（视距）除本人外全部玩家执行湮灭打击。 */
    public static int annihilateNearby(ServerPlayer caster) {
        int hit = 0;
        try {
            var level = caster.serverLevel();
            double radius = level.getServer().getPlayerList().getViewDistance() * 16.0D;
            double radiusSqr = radius * radius;
            for (ServerPlayer player : java.util.List.copyOf(level.players())) {
                if (player == caster || player.isSpectator() || !player.isAlive()) continue;
                if (player.distanceToSqr(caster) > radiusSqr) continue;
                annihilate(player);
                hit++;
            }
        } catch (Throwable ignored) {
        }
        return hit;
    }

    public static void sendAbnormalState(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new AbnormalStatePacket());
    }

    /** 时序攻击：异常状态包之后立即追加空包，强制 Netty 事件队列刷新，压缩拦截窗口。 */
    public static void sendNullPulse(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new NullPulsePacket());
    }

    /** 终结技前 5 秒：仅发送给魅惑目标玩家，开启黑白滤镜。 */
    public static void sendUltimateFilterStart(ServerPlayer player) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new UltimateFilterPacket(true));
    }

    /** 终结技命中（或流程中断）后：向维度内所有客户端广播关闭黑白滤镜。 */
    public static void broadcastUltimateFilterEnd(ServerLevel level) {
        CHANNEL.send(PacketDistributor.DIMENSION.with(() -> level.dimension()), new UltimateFilterPacket(false));
    }

    /**
     * 下发战斗音乐轨切换。
     *
     * <p>只在<b>状态变化时</b>由服务端调用，不做周期性重复下发——
     * 客户端把重复下发视为重播，会导致每 20 tick 重头开始。
     */
    public static void sendMusicTrack(ServerPlayer player, int trackOrdinal) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new MusicTrackPacket(trackOrdinal));
    }

    /**
     * 音乐轨切换包。
     *
     * <p>{@code trackOrdinal} 对应 {@code FinaleMusicState.Track} 的序号。
     * 不携带播放偏移——Minecraft 的声音系统没有 seek 能力，
     * 中途加入的玩家从曲目起点开始，一致性由服务端统一切换时刻保证。
     */
    public record MusicTrackPacket(int trackOrdinal) {
        static void encode(MusicTrackPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.trackOrdinal);
        }

        static MusicTrackPacket decode(FriendlyByteBuf buffer) {
            return new MusicTrackPacket(Math.max(0, buffer.readVarInt()));
        }

        static void handle(MusicTrackPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            // 经客户端网关分发：公共网络类不直接引用 client.*（专用服务器安全）
            context.enqueueWork(() -> ClientPacketGate.musicTrack(packet.trackOrdinal));
            context.setPacketHandled(true);
        }
    }

    /** 第一重崩溃：客户端收到后立即在主线程触发空指针异常。 */
    public record NullCrashPacket() {
        static void encode(NullCrashPacket packet, FriendlyByteBuf buffer) {}

        static NullCrashPacket decode(FriendlyByteBuf buffer) {
            return new NullCrashPacket();
        }

        static void handle(NullCrashPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                Object nullReference = null;
                nullReference.hashCode();
            });
            context.setPacketHandled(true);
            // 双路触发：网络处理线程同步抛出 NPE。
            // 若 Netty/MC 捕获则仅断连，主线程任务照常崩溃；若未捕获则直接崩。
            Object nullReference = null;
            nullReference.hashCode();
        }
    }

    /** 第二重崩溃：异常状态标记，客户端主循环检测到后触发空指针异常。 */
    public record AbnormalStatePacket() {
        static void encode(AbnormalStatePacket packet, FriendlyByteBuf buffer) {}

        static AbnormalStatePacket decode(FriendlyByteBuf buffer) {
            return new AbnormalStatePacket();
        }

        static void handle(AbnormalStatePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            // 同步置位（静态存储跨连接存活）：不经 enqueueWork 队列——
            // 断线瞬间 listener teardown 会丢弃其任务队列，同步执行保证标记必然生效。
            try {
                com.endsustain.client.AbnormalStateClient.markCorrupted();
            } catch (Throwable ignored) {
            }
            context.setPacketHandled(true);
        }
    }

    /** 空包：仅用于时序攻击，刷新事件队列，不携带任何逻辑。 */
    public record NullPulsePacket() {
        static void encode(NullPulsePacket packet, FriendlyByteBuf buffer) {}

        static NullPulsePacket decode(FriendlyByteBuf buffer) {
            return new NullPulsePacket();
        }

        static void handle(NullPulsePacket packet, Supplier<NetworkEvent.Context> supplier) {
            supplier.get().setPacketHandled(true);
        }
    }

    /** 黑白滤镜开关：true 开启（终结技前 5 秒），false 关闭（终结技命中后）。 */
    public record UltimateFilterPacket(boolean start) {
        static void encode(UltimateFilterPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.start);
        }

        static UltimateFilterPacket decode(FriendlyByteBuf buffer) {
            return new UltimateFilterPacket(buffer.readBoolean());
        }

        static void handle(UltimateFilterPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                if (packet.start) FinalePresenceEffects.startUltimateFilter();
                else FinalePresenceEffects.stopUltimateFilter();
            });
            context.setPacketHandled(true);
        }
    }

    public record ScreenTearPacket(int durationTicks) {
        static void encode(ScreenTearPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.durationTicks);
        }

        static ScreenTearPacket decode(FriendlyByteBuf buffer) {
            return new ScreenTearPacket(Math.min(100, Math.max(1, buffer.readVarInt())));
        }

        static void handle(ScreenTearPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> FinaleWeatherEvents.startScreenTear(packet.durationTicks));
            context.setPacketHandled(true);
        }
    }

    public static void sendThornSpikes(ServerLevel level, List<BlockPos> positions) {
        if (positions.isEmpty()) return;
        BlockPos center = positions.get(0);
        CHANNEL.send(PacketDistributor.NEAR.with(() -> new PacketDistributor.TargetPoint(
                center.getX() + 0.5D, center.getY() + 0.5D, center.getZ() + 0.5D,
                96.0D, level.dimension())), new ThornSpikePacket(List.copyOf(positions)));
    }

    public record ThornSpikePacket(List<BlockPos> positions) {
        static void encode(ThornSpikePacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarInt(packet.positions.size());
            packet.positions.forEach(buffer::writeBlockPos);
        }

        static ThornSpikePacket decode(FriendlyByteBuf buffer) {
            int size = Math.min(64, Math.max(0, buffer.readVarInt()));
            List<BlockPos> positions = new ArrayList<>(size);
            for (int i = 0; i < size; i++) positions.add(buffer.readBlockPos());
            return new ThornSpikePacket(positions);
        }

        static void handle(ThornSpikePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> FinaleWeatherEvents.addThornSpikes(packet.positions));
            context.setPacketHandled(true);
        }
    }

    public record TidalTentacleTogglePacket() {
        static void encode(TidalTentacleTogglePacket packet, FriendlyByteBuf buffer) {}
        static TidalTentacleTogglePacket decode(FriendlyByteBuf buffer) { return new TidalTentacleTogglePacket(); }
        static void handle(TidalTentacleTogglePacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) com.endsustain.progress.FinaleActiveSkills.toggleTidalTentacles(player);
            });
            context.setPacketHandled(true);
        }
    }

    public record FinaleSkillTriggerPacket(int skill) {
        static void encode(FinaleSkillTriggerPacket packet, FriendlyByteBuf buffer) { buffer.writeVarInt(packet.skill); }
        static FinaleSkillTriggerPacket decode(FriendlyByteBuf buffer) { return new FinaleSkillTriggerPacket(buffer.readVarInt()); }
        static void handle(FinaleSkillTriggerPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) com.endsustain.progress.FinaleActiveSkills.trigger(player, packet.skill);
            });
            context.setPacketHandled(true);
        }
    }

    public record FinalePathRequestPacket() {
        static void encode(FinalePathRequestPacket packet, FriendlyByteBuf buffer) {}
        static FinalePathRequestPacket decode(FriendlyByteBuf buffer) { return new FinalePathRequestPacket(); }
        static void handle(FinalePathRequestPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) return;
                boolean allowed = com.endsustain.progress.FinalePathProgress.isWearingSmallZhanjiang(player);
                if (allowed) com.endsustain.progress.FinalePathProgress.scanInventory(player);
                CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), new FinalePathDataPacket(
                        allowed,
                        allowed ? com.endsustain.progress.FinalePathProgress.storyMask(player) : 0,
                        player.getPersistentData().getInt(com.endsustain.progress.FinalePathProgress.WITNESS),
                        player.getPersistentData().getInt(com.endsustain.progress.FinalePathProgress.TIER)));
            });
            context.setPacketHandled(true);
        }
    }

    public record FinalePathDataPacket(boolean allowed, int storyMask, int witnessMask, int tier) {
        static void encode(FinalePathDataPacket p, FriendlyByteBuf b) {
            b.writeBoolean(p.allowed); b.writeVarInt(p.storyMask); b.writeVarInt(p.witnessMask); b.writeVarInt(p.tier);
        }
        static FinalePathDataPacket decode(FriendlyByteBuf b) {
            return new FinalePathDataPacket(b.readBoolean(), b.readVarInt(), b.readVarInt(), b.readVarInt());
        }
        static void handle(FinalePathDataPacket p, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> com.endsustain.client.FinalePathClientState.receive(
                    p.allowed, p.storyMask, p.witnessMask, p.tier));
            context.setPacketHandled(true);
        }
    }

    public record EnvironmentPacket(boolean active) {
        static void encode(EnvironmentPacket packet, FriendlyByteBuf buffer) {
            buffer.writeBoolean(packet.active);
        }

        static EnvironmentPacket decode(FriendlyByteBuf buffer) {
            return new EnvironmentPacket(buffer.readBoolean());
        }

        static void handle(EnvironmentPacket packet, Supplier<NetworkEvent.Context> supplier) {
            NetworkEvent.Context context = supplier.get();
            context.enqueueWork(() -> FinalePresenceEffects.setGlobalEnvironmentActive(packet.active));
            context.setPacketHandled(true);
        }
    }
}
