package com.endsustain.entity.boss;

import com.endsustain.EndSustain;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class DimensionPhaseManager {
    private static final String NETHER_STAGE = "EndsustainOfficialEnteredNether";
    private static final String OVERWORLD_STAGE = "EndsustainOfficialEnteredOverworld";
    private static final Map<UUID, PendingTransfer> PENDING = new HashMap<>();

    private DimensionPhaseManager() {}

    static boolean isTransitioning(FinaleEndsustainEntity boss) {
        return PENDING.containsKey(boss.getUUID())
                || boss.getPersistentData().getBoolean("EndsustainDimensionTransition");
    }

    static boolean tick(FinaleEndsustainEntity boss) {
        if (!(boss.level() instanceof ServerLevel source)) return false;
        PendingTransfer pending = PENDING.get(boss.getUUID());
        if (pending != null) {
            boss.getNavigation().stop();
            net.minecraft.world.phys.Vec3 doorway = net.minecraft.world.phys.Vec3.atCenterOf(pending.entrance);
            boss.setDeltaMovement(doorway.subtract(boss.position()).scale(0.22D));
            boss.hurtMarked = true;
            pending.ticks++;
            if (pending.ticks >= 20) {
                PENDING.remove(boss.getUUID());
                ServerLevel destination = source.getServer().getLevel(pending.dimension);
                if (destination != null) {
                    destination.getChunkAt(pending.position);
                    boolean moved = boss.teleportTo(destination,
                            pending.position.getX() + 0.5D,
                            pending.position.getY() + 0.1D,
                            pending.position.getZ() + 0.5D,
                            Set.of(), boss.getYRot(), boss.getXRot());
                    if (!moved && !boss.isRemoved()) {
                        Entity changed = boss.changeDimension(destination);
                        if (changed != null) {
                            changed.teleportTo(pending.position.getX() + 0.5D,
                                    pending.position.getY() + 0.1D,
                                    pending.position.getZ() + 0.5D);
                            changed.getPersistentData().putBoolean("EndsustainDimensionTransition", false);
                        }
                    }
                    boss.getPersistentData().putBoolean("EndsustainDimensionTransition", false);
                    EndSustain.LOGGER.info("[终焉维系] Boss 维度传送完成：目标维度={}，坐标={}，teleportTo={}",
                            pending.dimension.location(), pending.position, moved);
                }
            }
            return true;
        }

        if (source.dimension() == Level.END
                && boss.getHealth() <= boss.getMaxHealth() * 0.66F
                && !boss.getPersistentData().getBoolean(NETHER_STAGE)) {
            ServerLevel nether = source.getServer().getLevel(Level.NETHER);
            if (nether != null) {
                BlockPos destination = new BlockPos(boss.blockPosition().getX(), 129, boss.blockPosition().getZ());
                if (openPortalPair(boss, source, nether, destination)) {
                    boss.getPersistentData().putBoolean(NETHER_STAGE, true);
                    boss.getPersistentData().putBoolean("EndsustainDimensionTransition", true);
                    boss.setOfficialPhase(1);
                    PENDING.put(boss.getUUID(), new PendingTransfer(Level.NETHER, destination,
                            boss.blockPosition().relative(boss.getDirection(), 2).above()));
                    return true;
                }
            }
        }

        if (source.dimension() == Level.NETHER
                && boss.getHealth() <= boss.getMaxHealth() * 0.33F
                && !boss.getPersistentData().getBoolean(OVERWORLD_STAGE)) {
            ServerLevel overworld = source.getServer().getLevel(Level.OVERWORLD);
            if (overworld != null) {
                BlockPos destination = locateSkyArena(overworld, boss.blockPosition());
                if (openPortalPair(boss, source, overworld, destination)) {
                    boss.getPersistentData().putBoolean(OVERWORLD_STAGE, true);
                    boss.getPersistentData().putBoolean("EndsustainDimensionTransition", true);
                    boss.setOfficialPhase(2);
                    PENDING.put(boss.getUUID(), new PendingTransfer(Level.OVERWORLD, destination,
                            boss.blockPosition().relative(boss.getDirection(), 2).above()));
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos locateSkyArena(ServerLevel overworld, BlockPos origin) {
        ResourceKey<Structure> key = ResourceKey.create(Registries.STRUCTURE,
                new ResourceLocation("skyarena", "sky_arena"));
        var registry = overworld.registryAccess().registryOrThrow(Registries.STRUCTURE);
        var holder = registry.getHolder(key);
        if (holder.isPresent()) {
            Pair<BlockPos, Holder<Structure>> located = overworld.getChunkSource().getGenerator()
                    .findNearestMapStructure(overworld, HolderSet.direct(holder.get()), origin, 128, false);
            if (located != null) {
                BlockPos altar = findSkyArenaAltar(overworld, located.getFirst(), holder.get().value());
                if (altar != null) {
                    EndSustain.LOGGER.info("[终焉维系] 已定位 skyarena:altar_battle：{}", altar);
                    return altar.above();
                }
                EndSustain.LOGGER.warn("已定位 skyarena:sky_arena，但结构内未找到 skyarena:altar_battle");
                return located.getFirst().above(2);
            }
        }
        int y = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                origin.getX(), origin.getZ());
        EndSustain.LOGGER.warn("未定位到 skyarena:sky_arena，使用主世界安全高度兜底");
        return new BlockPos(origin.getX(), y, origin.getZ());
    }

    private static BlockPos findSkyArenaAltar(ServerLevel level, BlockPos located,
                                              Structure structure) {
        Block altar = ForgeRegistries.BLOCKS.getValue(new ResourceLocation("skyarena", "altar_battle"));
        if (altar == null) return null;
        level.getChunkAt(located);
        java.util.List<net.minecraft.world.level.levelgen.structure.StructureStart> starts =
                level.structureManager().startsForStructure(SectionPos.of(located), structure);
        net.minecraft.world.level.levelgen.structure.StructureStart start = starts.stream()
                .filter(net.minecraft.world.level.levelgen.structure.StructureStart::isValid)
                .findFirst().orElse(null);
        net.minecraft.world.level.levelgen.structure.BoundingBox box = start == null
                ? new net.minecraft.world.level.levelgen.structure.BoundingBox(
                        located.getX() - 80, level.getMinBuildHeight(), located.getZ() - 80,
                        located.getX() + 80, level.getMaxBuildHeight() - 1, located.getZ() + 80)
                : start.getBoundingBox();
        for (int chunkX = SectionPos.blockToSectionCoord(box.minX());
             chunkX <= SectionPos.blockToSectionCoord(box.maxX()); chunkX++) {
            for (int chunkZ = SectionPos.blockToSectionCoord(box.minZ());
                 chunkZ <= SectionPos.blockToSectionCoord(box.maxZ()); chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
        EndSustain.LOGGER.info("[终焉维系] 扫描天空竞技场边界：{}", box);
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        BlockPos nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int z = box.minZ(); z <= box.maxZ(); z++) {
                for (int y = box.maxY(); y >= box.minY(); y--) {
                    cursor.set(x, y, z);
                    if (!level.getBlockState(cursor).is(altar)) continue;
                    double distance = cursor.distSqr(located);
                    if (distance < nearestDistance) {
                        nearestDistance = distance;
                        nearest = cursor.immutable();
                    }
                }
            }
        }
        return nearest;
    }

    private static boolean openPortalPair(FinaleEndsustainEntity boss, ServerLevel source,
                                          ServerLevel destinationLevel, BlockPos destination) {
        try {
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                    new ResourceLocation("alexsmobs", "void_portal"));
            if (type == null) return false;
            Entity entrance = type.create(source);
            if (entrance == null) return false;

            BlockPos entrancePos = boss.blockPosition().relative(boss.getDirection(), 2).above();
            entrance.setPos(entrancePos.getX() + 0.5D, entrancePos.getY(), entrancePos.getZ() + 0.5D);
            configurePortal(entrance, destinationLevel.dimension(), destination);
            source.addFreshEntity(entrance);
            source.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                    entrance.getX(), entrance.getY() + 1.0D, entrance.getZ(),
                    180, 1.2D, 1.6D, 1.2D, 0.25D);
            source.playSound(null, entrance.blockPosition(),
                    net.minecraft.sounds.SoundEvents.END_PORTAL_SPAWN,
                    net.minecraft.sounds.SoundSource.HOSTILE, 2.0F, 0.65F);
            return true;
        } catch (ReflectiveOperationException exception) {
            EndSustain.LOGGER.error("创建 Alexs Mobs 单向破碎维度传送门失败", exception);
            return false;
        }
    }

    private static void configurePortal(Entity portal, ResourceKey<Level> exitDimension,
                                        BlockPos destination) throws ReflectiveOperationException {
        Method setShattered = portal.getClass().getMethod("setShattered", boolean.class);
        Method setLifespan = portal.getClass().getMethod("setLifespan", int.class);
        Method setDestination = portal.getClass().getMethod("setDestination", BlockPos.class);
        Method setAttachment = portal.getClass().getMethod("setAttachmentFacing", Direction.class);
        Field dimension = portal.getClass().getField("exitDimension");
        setShattered.invoke(portal, true);
        setLifespan.invoke(portal, 20 * 60);
        setDestination.invoke(portal, destination);
        setAttachment.invoke(portal, Direction.NORTH);
        dimension.set(portal, exitDimension);
    }

    private static final class PendingTransfer {
        private final ResourceKey<Level> dimension;
        private final BlockPos position;
        private final BlockPos entrance;
        private int ticks;

        private PendingTransfer(ResourceKey<Level> dimension, BlockPos position, BlockPos entrance) {
            this.dimension = dimension;
            this.position = position;
            this.entrance = entrance;
        }
    }
}
