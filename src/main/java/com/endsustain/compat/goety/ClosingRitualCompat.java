package com.endsustain.compat.goety;

import com.endsustain.EndSustain;
import com.endsustain.item.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.lang.reflect.Proxy;

public final class ClosingRitualCompat {
    public static final String RITUAL_NAME = "closing";
    private static boolean registered;

    private ClosingRitualCompat() {}

    public static void register() {
        if (registered) return;
        try {
            Class<?> ritualInterface = Class.forName("com.Polarice3.Goety.api.ritual.IRitualType");
            Class<?> ritualTypeClass = Class.forName("com.Polarice3.Goety.api.ritual.RitualType");

            Object ritualType = Proxy.newProxyInstance(
                    ritualInterface.getClassLoader(),
                    new Class<?>[]{ritualInterface},
                    (proxy, method, args) -> {
                        String name = method.getName();
                        if ("getName".equals(name)) return RITUAL_NAME;
                        if ("getJeiIcon".equals(name)) return new ItemStack(ModItems.ENDSUSTAIN_CORE.get());
                        if ("getRequirement".equals(name)) {
                            BlockPos altarPos = null;
                            Level level = null;
                            Player player = null;
                            if (args != null) {
                                for (Object argument : args) {
                                    if (argument instanceof BlockPos pos) altarPos = pos;
                                    else if (argument instanceof Level foundLevel) level = foundLevel;
                                    else if (argument instanceof Player foundPlayer) player = foundPlayer;
                                }
                            }
                            return altarPos != null && level != null && checkRequirements(level, altarPos, player);
                        }
                        if ("sendFinishRay".equals(name) || "onFinishRitual".equals(name)) {
                            if (args != null && args.length >= 2 && args[0] instanceof Level level && args[1] instanceof BlockPos pos) {
                                playFinishEffects(level, pos);
                            }
                            if ("onFinishRitual".equals(name) && args != null) {
                                for (Object argument : args) {
                                    if (argument instanceof Player player) {
                                        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                                            com.endsustain.progress.FinaleMilestones.award(serverPlayer,
                                                    com.endsustain.progress.FinaleMilestones.RITUAL_STARTED);
                                        }
                                        giveEmergencyLogoutDevice(player);
                                        break;
                                    }
                                }
                            }
                            return null;
                        }
                        if ("toString".equals(name)) return "EndsustainClosingRitualType";
                        if ("hashCode".equals(name)) return System.identityHashCode(proxy);
                        if ("equals".equals(name)) return proxy == args[0];
                        if (method.getReturnType() == boolean.class) return false;
                        if (method.getReturnType() == int.class) return 0;
                        return null;
                    }
            );

            ritualTypeClass.getMethod("addRitualType", String.class, ritualInterface)
                    .invoke(null, RITUAL_NAME, ritualType);
            registered = true;
            EndSustain.LOGGER.info("[落幕终焉] 已注册 Goety 自定义仪式类型：落幕仪式");
        } catch (Throwable t) {
            EndSustain.LOGGER.error("[落幕终焉] 注册 Goety 落幕仪式失败: {}", t.toString());
        }
    }

    private static boolean checkRequirements(Level level, BlockPos altarPos, @Nullable Player player) {
        if (level.dimension() != Level.END) {
            notifyFailure(player, "落幕仪式只能在末地进行！");
            return false;
        }

        int arcaneAnvils = 0;
        int darkAnvils = 0;
        int vanillaAnvils = 0;
        int endStoneBricks = 0;
        int amethystBlocks = 0;
        int cryingObsidian = 0;
        int obsidian = 0;
        int dragonEggs = 0;
        int endRods = 0;
        int transmutationTables = 0;
        int nightBeacons = 0;

        BlockPos min = altarPos.offset(-8, -4, -8);
        BlockPos max = altarPos.offset(8, 4, 8);
        for (BlockPos scanPos : BlockPos.betweenClosed(min, max)) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(level.getBlockState(scanPos).getBlock());
            if (blockId == null) continue;
            String id = blockId.toString();
            switch (id) {
                case "irons_spellbooks:arcane_anvil" -> arcaneAnvils++;
                case "goety:dark_anvil" -> darkAnvils++;
                case "minecraft:anvil" -> vanillaAnvils++;
                case "minecraft:end_stone_bricks" -> endStoneBricks++;
                case "minecraft:amethyst_block" -> amethystBlocks++;
                case "minecraft:crying_obsidian" -> cryingObsidian++;
                case "minecraft:obsidian" -> obsidian++;
                case "minecraft:dragon_egg" -> dragonEggs++;
                case "minecraft:end_rod" -> endRods++;
                case "alexsmobs:transmutation_table" -> transmutationTables++;
                case "goety:night_beacon" -> nightBeacons++;
            }
        }

        if (arcaneAnvils < 1) {
            notifyFailure(player, "落幕仪式缺少铁魔法的奥术铁砧！");
            return false;
        }
        if (darkAnvils < 1) {
            notifyFailure(player, "落幕仪式缺少诡厄巫法的黑暗铁砧！");
            return false;
        }
        if (vanillaAnvils < 1) {
            notifyFailure(player, "落幕仪式缺少原版铁砧！");
            return false;
        }
        if (endStoneBricks < 20) {
            notifyFailure(player, "落幕仪式需要至少 20 个末地石砖，当前只有 " + endStoneBricks + " 个！");
            return false;
        }
        if (amethystBlocks < 8) {
            notifyFailure(player, "落幕仪式需要至少 8 个紫水晶块，当前只有 " + amethystBlocks + " 个！");
            return false;
        }
        if (cryingObsidian < 12) {
            notifyFailure(player, "落幕仪式需要至少 12 个哭泣的黑曜石，当前只有 " + cryingObsidian + " 个！");
            return false;
        }
        if (obsidian < 20) {
            notifyFailure(player, "落幕仪式需要至少 20 个黑曜石，当前只有 " + obsidian + " 个！");
            return false;
        }
        if (dragonEggs < 4) {
            notifyFailure(player, "落幕仪式需要至少 4 个龙蛋，当前只有 " + dragonEggs + " 个！");
            return false;
        }
        if (endRods < 4) {
            notifyFailure(player, "落幕仪式需要至少 4 根末地烛，当前只有 " + endRods + " 根！");
            return false;
        }
        if (transmutationTables < 4) {
            notifyFailure(player, "落幕仪式需要至少 4 个嬗变台，当前只有 " + transmutationTables + " 个！");
            return false;
        }
        if (nightBeacons < 4) {
            notifyFailure(player, "落幕仪式需要至少 4 个暗夜信标，当前只有 " + nightBeacons + " 个！");
            return false;
        }
        return true;
    }

    private static void giveEmergencyLogoutDevice(Player player) {
        ItemStack device = new ItemStack(ModItems.EMERGENCY_LOGOUT_DEVICE.get());
        if (!player.getInventory().add(device)) {
            player.drop(device, false);
        }
        player.displayClientMessage(Component.literal("落幕仪式完成：你获得了起爆器。"), false);
    }

    private static void notifyFailure(@Nullable Player player, String message) {
        if (player != null) player.displayClientMessage(Component.literal(message), true);
    }

    private static void playFinishEffects(Level level, BlockPos pos) {
        if (!(level instanceof ServerLevel server)) return;
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 1.0D;
        double z = pos.getZ() + 0.5D;
        server.sendParticles(ParticleTypes.REVERSE_PORTAL, x, y, z, 160, 2.5D, 1.5D, 2.5D, 0.3D);
        server.sendParticles(ParticleTypes.END_ROD, x, y, z, 80, 1.5D, 2.5D, 1.5D, 0.18D);
        server.playSound(null, pos, SoundEvents.END_PORTAL_SPAWN, SoundSource.HOSTILE, 4.0F, 0.65F);
    }
}
