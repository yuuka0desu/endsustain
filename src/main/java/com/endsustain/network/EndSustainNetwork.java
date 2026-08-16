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
