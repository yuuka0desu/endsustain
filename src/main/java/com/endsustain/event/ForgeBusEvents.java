package com.endsustain.event;

import com.endsustain.EndSustain;
import com.endsustain.FinaleEnvironmentState;
import com.endsustain.combat.TrueKillUtil;
import com.endsustain.compat.CompatHandler;
import com.endsustain.entity.boss.EndsustainBladeEntity;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.entity.boss.SpellProjectile;
import com.endsustain.item.ModItems;
import com.endsustain.item.weapon.EndsustainBladeItem;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingDropsEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.server.ServerAboutToStartEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FORGE 总线监听：在服务器完全加载后才初始化 compat，避开所有配置/注册阶段。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeBusEvents {

    private static boolean compatInitialized = false;
    private static final Map<UUID, FinalePresence> FINALE_PRESENCE = new HashMap<>();

    public static void observeFinaleBoss(FinaleEndsustainEntity boss) {
        if (boss.level().isClientSide || boss.isRemoved()) return;
        if (!boss.isEnvironmentActive()) return;
        FinaleEnvironmentState.setPresenceState(boss.level().getServer(), true);
        FINALE_PRESENCE.put(boss.getUUID(), new FinalePresence(
                boss.level().dimension(), boss.blockPosition(), boss.position(),
                boss.getLastAttackingPlayer() == null ? null : boss.getLastAttackingPlayer().getUUID(),
                false));
    }

    public static void markFinaleRemoval(FinaleEndsustainEntity boss, Entity.RemovalReason reason) {
        FinalePresence presence = FINALE_PRESENCE.get(boss.getUUID());
        if (presence == null) return;
        if (reason != Entity.RemovalReason.KILLED
                || boss.isCombatDeathTailResolved()
                || reason == Entity.RemovalReason.DISCARDED
                || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK
                || reason == Entity.RemovalReason.CHANGED_DIMENSION) {
            presence.benignRemoval = true;
        }
    }

    private static void tickFinalePresence(MinecraftServer server) {
        var iterator = FINALE_PRESENCE.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            FinalePresence presence = entry.getValue();
            ServerLevel level = server.getLevel(presence.dimension);
            if (level == null || !level.hasChunkAt(presence.blockPos)) {
                iterator.remove();
                FinaleEnvironmentState.setPresenceState(server, false);
                continue;
            }
            Entity current = level.getEntity(entry.getKey());
            if (current != null && !current.isRemoved()) continue;
            iterator.remove();
            FinaleEnvironmentState.setPresenceState(server, false);
            if (!presence.benignRemoval) recoverMissingFinale(server, presence);
        }
    }

    private static void recoverMissingFinale(MinecraftServer server, FinalePresence presence) {
        ServerLevel level = server.getLevel(presence.dimension);
        if (level == null) return;
        Player target = presence.targetUuid == null ? null : server.getPlayerList().getPlayer(presence.targetUuid);
        if (target == null || target.level() != level || !target.isAlive()) {
            target = level.getNearestPlayer(presence.position.x, presence.position.y, presence.position.z, 96.0D, false);
        }
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(
                new ResourceLocation("revelationfix", "star_arrow"));
        if (type != null && target != null) {
            for (int i = 0; i < 10; i++) {
                Vec3 spawn = presence.position.add((level.random.nextDouble() - 0.5D) * 1.5D,
                        1.8D + i * 0.03D, (level.random.nextDouble() - 0.5D) * 1.5D);
                Vec3 aim = target.getEyePosition().subtract(spawn).normalize();
                Entity arrow = type.create(level);
                if (arrow == null) continue;
                arrow.setPos(spawn.x, spawn.y, spawn.z);
                arrow.addTag("endsustain_star_arrow");
                arrow.addTag("endsustain_recovered_tail");
                if (arrow instanceof Projectile projectile) projectile.shoot(aim.x, aim.y, aim.z, 3.0F, 0.2F);
                else arrow.setDeltaMovement(aim.scale(3.0D));
                level.addFreshEntity(arrow);
            }
        }
        for (Player player : level.players()) {
            double dx = player.getX() - presence.position.x;
            double dy = player.getY() - presence.position.y;
            double dz = player.getZ() - presence.position.z;
            if (dx * dx + dy * dy + dz * dz <= 9.0D) {
                TrueKillUtil.forceKill(player, level.damageSources().fellOutOfWorld(), null,
                        (float) Integer.MAX_VALUE, false);
            }
        }
        level.sendParticles(ParticleTypes.END_ROD, presence.position.x, presence.position.y + 1.0D,
                presence.position.z, 40, 0.7D, 0.7D, 0.7D, 0.12D);
        EndSustain.LOGGER.warn("蘸酱实体在已加载维度异常消失，已恢复尾杀：维度={}，位置={}",
                presence.dimension.location(), presence.position);
    }

    private static final class FinalePresence {
        private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
        private final BlockPos blockPos;
        private final Vec3 position;
        private final UUID targetUuid;
        private boolean benignRemoval;
        private FinalePresence(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                               BlockPos blockPos, Vec3 position, UUID targetUuid, boolean benignRemoval) {
            this.dimension = dimension; this.blockPos = blockPos; this.position = position;
            this.targetUuid = targetUuid; this.benignRemoval = benignRemoval;
        }
    }

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!compatInitialized) {
            compatInitialized = true;
            // 此时所有 mod、配置、资源均已完成加载
            CompatHandler.init(null);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            TrueKillUtil.tickPendingDeaths(event.getServer());
            EndsustainBladeEntity.tickPresence(event.getServer());
            tickFinalePresence(event.getServer());
            com.endsustain.combat.TimeStopManager.tick(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onOrangeChat(ServerChatEvent event) {
        if (!"橙".equals(event.getRawText())) return;
        ServerPlayer player = event.getPlayer();
        ItemStack ilusi = new ItemStack(ModItems.ILUSI.get());
        if (!player.getInventory().add(ilusi)) {
            player.drop(ilusi, false);
        }
        player.sendSystemMessage(Component.literal("你获得了 ilusi 可爱橙。"));
    }

    @SubscribeEvent
    public static void onPigNamedVoodom(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        if (!(event.getTarget() instanceof Pig pig)) return;
        ItemStack held = event.getItemStack();
        if (!held.is(Items.NAME_TAG) || !held.hasCustomHoverName()) return;
        if (!"VOODOM".equals(held.getHoverName().getString())) return;
        if (pig.getTags().contains("endsustain_voodom_blessed")) return;

        pig.addTag("endsustain_voodom_blessed");
        Player player = event.getEntity();
        ItemStack blessing = new ItemStack(ModItems.HAKI_WITCH_PIG_BLESSING.get());
        if (!player.getInventory().add(blessing)) {
            player.drop(blessing, false);
        }
        player.sendSystemMessage(Component.literal("VOODOM 感受到了你的呼唤。"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void giveFinaleDropsToPlayer(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof FinaleEndsustainEntity boss)) return;
        net.minecraft.world.item.Item halo = ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("goety_revelation", "halo_of_the_end"));
        // 每一次死亡掉落事件都先移除专属地面掉落，再判断背包奖励事务是否已经完成。
        event.getDrops().removeIf(drop -> isFinaleExclusiveDrop(drop.getItem(), halo));
        if (boss.getPersistentData().getBoolean("EndsustainDropsGranted")) return;

        ServerPlayer recipient = event.getSource().getEntity() instanceof ServerPlayer sourcePlayer
                ? sourcePlayer
                : boss.getLastHurtByMob() instanceof ServerPlayer lastPlayer ? lastPlayer : null;
        if (recipient == null && boss.level() instanceof ServerLevel level) {
            recipient = level.getEntitiesOfClass(ServerPlayer.class, boss.getBoundingBox().inflate(64.0D),
                    player -> player.isAlive() && !player.isSpectator()).stream().findFirst().orElse(null);
        }
        if (recipient == null) return;
        boss.getPersistentData().putBoolean("EndsustainDropsGranted", true);

        giveFinaleReward(recipient, new ItemStack(ModItems.ENDSUSTAIN_CORE.get()));

        net.minecraft.resources.ResourceLocation enhancedSharpnessId =
                new net.minecraft.resources.ResourceLocation("tonsofenchants", "enhanced_sharpness");
        net.minecraft.world.item.enchantment.Enchantment enhancedSharpness =
                ForgeRegistries.ENCHANTMENTS.getValue(enhancedSharpnessId);
        ItemStack sharpnessBook = new ItemStack(Items.ENCHANTED_BOOK);
        if (enhancedSharpness != null) {
            EnchantedBookItem.addEnchantment(sharpnessBook,
                    new net.minecraft.world.item.enchantment.EnchantmentInstance(enhancedSharpness, 32767));
        } else {
            EndSustain.LOGGER.error("未找到 {}，锋利奖励回退为原版 Sharpness 32767", enhancedSharpnessId);
            EnchantedBookItem.addEnchantment(sharpnessBook,
                    new net.minecraft.world.item.enchantment.EnchantmentInstance(Enchantments.SHARPNESS, 32767));
        }
        giveFinaleReward(recipient, sharpnessBook);

        ItemStack powerBook = new ItemStack(Items.ENCHANTED_BOOK);
        EnchantedBookItem.addEnchantment(powerBook,
                new net.minecraft.world.item.enchantment.EnchantmentInstance(Enchantments.POWER_ARROWS, 32767));
        giveFinaleReward(recipient, powerBook);

        if (halo != null) giveFinaleReward(recipient, new ItemStack(halo));
        recipient.sendSystemMessage(Component.literal("末影蘸酱的专属掉落已直接放入你的背包。"));
    }

    private static boolean isFinaleExclusiveDrop(ItemStack stack, net.minecraft.world.item.Item halo) {
        if (stack.is(ModItems.ENDSUSTAIN_CORE.get()) || halo != null && stack.is(halo)) return true;
        if (!stack.is(Items.ENCHANTED_BOOK)) return false;
        java.util.Map<net.minecraft.world.item.enchantment.Enchantment, Integer> enchantments =
                EnchantmentHelper.getEnchantments(stack);
        net.minecraft.world.item.enchantment.Enchantment enhancedSharpness =
                ForgeRegistries.ENCHANTMENTS.getValue(new net.minecraft.resources.ResourceLocation(
                        "tonsofenchants", "enhanced_sharpness"));
        return enchantments.containsKey(enhancedSharpness)
                || enchantments.containsKey(Enchantments.SHARPNESS)
                || enchantments.getOrDefault(Enchantments.POWER_ARROWS, 0) >= 255;
    }

    private static void giveFinaleReward(ServerPlayer player, ItemStack reward) {
        if (reward.isEmpty()) return;
        player.getInventory().placeItemBackInInventory(reward.copy());
        player.inventoryMenu.broadcastChanges();
    }

    /**
     * 终焉伤害目标的死亡事件兜底：以最低优先级执行，撤销其它模组对
     * LivingDeathEvent 的取消，保证死亡掉落（含 Boss 专属背包奖励）正常发生。
     * dead 标志在事件 post 之前已置位，本处理器只负责恢复死亡后事。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onTerminalDeathFinalization(LivingDeathEvent event) {
        if (event.getEntity().getPersistentData()
                .getBoolean(TrueKillUtil.TERMINAL_STATE_KEY)) {
            event.setCanceled(false);
        }
    }

    @SubscribeEvent
    public static void onQunUDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof com.endsustain.entity.boss.QunUEntity qun)) return;
        if (!(qun.level() instanceof ServerLevel level) || qun.getOwnerBoss() == null) return;
        Entity owner = level.getEntity(qun.getOwnerBoss());
        if (owner instanceof FinaleEndsustainEntity boss) {
            if (event.getSource().getEntity() instanceof ServerPlayer player) {
                boss.addSleepDamageBonus(player.getUUID());
            }
            boss.onQunUKilled(qun.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onFinaleIncomingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof FinaleEndsustainEntity boss
                && event.getSource().getEntity() instanceof ServerPlayer attacker) {
            boss.wakeFromPlayerAttack(attacker);
        }
        if (event.getEntity() instanceof Player player && isFinaleDamage(event.getSource())) {
            player.invulnerableTime = 0;
            player.setInvulnerable(false);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onFinaleDamageHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof FinaleEndsustainEntity boss
                && event.getSource().getEntity() instanceof ServerPlayer player) {
            int stacks = boss.getSleepDamageBonusStacks(player.getUUID());
            if (stacks > 0 && boss.canReceiveDamageNow()) {
                event.setAmount(event.getAmount() * (float) Math.pow(1.10D, stacks));
                boss.clearSleepDamageBonusStacks(player.getUUID());
            }
        }
        if (event.getEntity() instanceof Player player && isFinaleDamage(event.getSource())) {
            player.invulnerableTime = 0;
            player.setInvulnerable(false);
        }
    }

    /** RevelationCage 使用 actuallyHurt 绕过 LivingHurtEvent；在实际扣血后补接尾杀入口。 */
    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void interceptFinaleDamageAfterActuallyHurt(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof FinaleEndsustainEntity boss)
                || boss.isCombatDeathTailStarted() || boss.isCombatDeathTailResolved()
                || boss.getHealth() > 0.0F) return;
        if (event.getSource().getEntity() instanceof ServerPlayer player) {
            boss.setLastHurtByPlayer(player);
        }
        boss.setTailKillDoom(true);
        if (boss.beginCombatDeathTail(event.getSource())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void interceptFinaleCombatDeath(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof FinaleEndsustainEntity boss)
                || boss.isCombatDeathTailStarted() || boss.isCombatDeathTailResolved()) return;
        if (event.getAmount() < boss.getHealth()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer player) boss.setLastHurtByPlayer(player);
        boss.setTailKillDoom(true);
        boss.beginCombatDeathTail(event.getSource());
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void interceptFinaleCombatDeathEvent(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof FinaleEndsustainEntity boss)
                || boss.isCombatDeathTailStarted() || boss.isCombatDeathTailResolved()) return;
        if (event.getSource().getEntity() instanceof ServerPlayer player) boss.setLastHurtByPlayer(player);
        boss.setTailKillDoom(true);
        if (boss.beginCombatDeathTail(event.getSource())) event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverflowBladeAttackEarly(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof LivingEntity target)
                || !player.getMainHandItem().is(ModItems.ENDSUSTAIN_BLADE.get())
                || !EndsustainBladeItem.hasOverflowDamage(player)
                || target.getPersistentData().getBoolean("EndsustainTrueKillRunning")
                || TrueKillUtil.isPending(target)
                || target instanceof FinaleEndsustainEntity) return;
        target.getPersistentData().putBoolean("EndsustainTrueKillRunning", true);
        try {
            com.endsustain.EndSustain.LOGGER.info("终焉之刃溢出近战命中：玩家={}，目标={}，游戏分钟={}，基础伤害={}",
                    player.getGameProfile().getName(), target.getType(),
                    EndsustainBladeItem.getPlayTimeMinutes(player), EndsustainBladeItem.getBladeDamageLong(player));
            TrueKillUtil.forceKill(target, player.damageSources().playerAttack(player), player,
                    Float.MAX_VALUE, true);
            event.setCanceled(true);
        } finally {
            if (!target.isRemoved()) target.getPersistentData().remove("EndsustainTrueKillRunning");
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onEndsustainBladeDamage(LivingHurtEvent event) {
        DamageSource source = event.getSource();
        if (!(source.getEntity() instanceof ServerPlayer player)) return;

        ItemStack bladeStack;
        Entity direct = source.getDirectEntity();
        if (direct == player && player.getMainHandItem().is(ModItems.ENDSUSTAIN_BLADE.get())) {
            bladeStack = player.getMainHandItem();
        } else if (direct instanceof EndsustainBladeEntity blade
                && blade.getTags().contains(EndsustainBladeItem.THROW_TAG)) {
            CompoundTag saved = blade.getPersistentData().getCompound(EndsustainBladeItem.STACK_DATA_KEY);
            if (saved.isEmpty()) return;
            bladeStack = ItemStack.of(saved);
        } else {
            return;
        }

        long baseDamage = EndsustainBladeItem.getBladeDamageLong(player);
        float enchantmentBonus = EnchantmentHelper.getDamageBonus(bladeStack, event.getEntity().getMobType());
        event.setAmount((float) Math.max(0.0D, baseDamage + enchantmentBonus));
        spawnEchoingStrikeEffect(event.getEntity(), player);

        if (baseDamage > Integer.MAX_VALUE && !(event.getEntity() instanceof FinaleEndsustainEntity)) {
            TrueKillUtil.forceKill(event.getEntity(), player.damageSources().playerAttack(player), player,
                    2_100_000_000.0F, true);
        }
    }

    private static void spawnEchoingStrikeEffect(LivingEntity target, LivingEntity owner) {
        if (!(target.level() instanceof ServerLevel server)) return;
        try {
            Class<?> echoClass = Class.forName("io.redspace.ironsspellbooks.entity.spells.EchoingStrikeEntity");
            Object created = echoClass
                    .getConstructor(net.minecraft.world.level.Level.class, LivingEntity.class, float.class, float.class)
                    .newInstance(server, owner, 0.0F, 3.0F);
            if (created instanceof Entity echo) {
                echo.setPos(target.getX(), target.getY(), target.getZ());
                server.addFreshEntity(echo);
            }
        } catch (Throwable t) {
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                    target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ(),
                    24, 0.6D, 0.6D, 0.6D, 0.12D);
        }
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void onFinaleDamageApplied(LivingDamageEvent event) {
        if (event.getEntity() instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity) {
            event.setCanceled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && isFinaleDamage(event.getSource())) {
            player.invulnerableTime = 0;
            player.setInvulnerable(false);
        }
    }


    private static boolean isFinaleDamage(DamageSource source) {
        Entity attacker = source.getEntity();
        Entity direct = source.getDirectEntity();
        return attacker instanceof FinaleEndsustainEntity
                || direct instanceof FinaleEndsustainEntity
                || direct instanceof SpellProjectile
                || direct instanceof EndsustainBladeEntity;
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("endsustain_test_ultimate")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> triggerNearestFinaleUltimate(ctx.getSource())));
        event.getDispatcher().register(Commands.literal("endsustain_test_charm")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> triggerNearestFinaleCharm(ctx.getSource())));
    }

    private static int triggerNearestFinaleCharm(net.minecraft.commands.CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var origin = source.getPosition();
        ServerPlayer nearestPlayer = null;
        double nearestPlayerDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double dist = player.distanceToSqr(origin);
            if (dist < nearestPlayerDist) {
                nearestPlayerDist = dist;
                nearestPlayer = player;
            }
        }
        if (nearestPlayer == null) {
            source.sendFailure(Component.literal("未找到可作为魅惑目标的玩家"));
            return 0;
        }

        FinaleEndsustainEntity nearestBoss = null;
        double nearestBossDist = Double.MAX_VALUE;
        for (FinaleEndsustainEntity boss : level.getEntitiesOfClass(FinaleEndsustainEntity.class,
                net.minecraft.world.phys.AABB.ofSize(origin, 256.0D, 256.0D, 256.0D),
                FinaleEndsustainEntity::isAlive)) {
            double dist = boss.distanceToSqr(origin);
            if (dist < nearestBossDist) {
                nearestBossDist = dist;
                nearestBoss = boss;
            }
        }
        if (nearestBoss == null) {
            source.sendFailure(Component.literal("未在 128 格内找到落幕之终焉 Boss"));
            return 0;
        }

        nearestBoss.setTarget(nearestPlayer);
        nearestBoss.beginCharm(nearestPlayer);
        String targetName = nearestPlayer.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal("已强制最近的落幕之终焉对最近玩家 "
                + targetName + " 释放魅惑+终结技组合"), true);
        return Command.SINGLE_SUCCESS;
    }

    private static int triggerNearestFinaleUltimate(net.minecraft.commands.CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        var origin = source.getPosition();
        ServerPlayer nearestPlayer = null;
        double nearestPlayerDist = Double.MAX_VALUE;
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double dist = player.distanceToSqr(origin);
            if (dist < nearestPlayerDist) {
                nearestPlayerDist = dist;
                nearestPlayer = player;
            }
        }
        if (nearestPlayer == null) {
            source.sendFailure(Component.literal("未找到可作为必杀目标的玩家"));
            return 0;
        }

        FinaleEndsustainEntity nearestBoss = null;
        double nearestBossDist = Double.MAX_VALUE;
        for (FinaleEndsustainEntity boss : level.getEntitiesOfClass(FinaleEndsustainEntity.class,
                net.minecraft.world.phys.AABB.ofSize(origin, 256.0D, 256.0D, 256.0D),
                FinaleEndsustainEntity::isAlive)) {
            double dist = boss.distanceToSqr(origin);
            if (dist < nearestBossDist) {
                nearestBossDist = dist;
                nearestBoss = boss;
            }
        }
        if (nearestBoss == null) {
            source.sendFailure(Component.literal("未在 128 格内找到落幕之终焉 Boss"));
            return 0;
        }

        nearestBoss.setTarget(nearestPlayer);
        nearestBoss.beginUltimate(nearestPlayer);
        String targetName = nearestPlayer.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal("已强制最近的落幕之终焉对最近玩家 "
                + targetName + " 释放必杀技"), true);
        return Command.SINGLE_SUCCESS;
    }
}
