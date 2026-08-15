package com.endsustain.event;

import com.endsustain.EndSustain;
import com.endsustain.combat.TrueKillUtil;
import com.endsustain.compat.CompatHandler;
import com.endsustain.compat.ysm.YsmCompat;
import com.endsustain.entity.boss.EndsustainBladeEntity;
import com.endsustain.entity.boss.FinaleEndsustainEntity;
import com.endsustain.entity.boss.SpellProjectile;
import com.endsustain.item.ModItems;
import com.endsustain.item.weapon.EndsustainBladeItem;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.EnchantedBookItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.ServerChatEvent;
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

/**
 * FORGE 总线监听：在服务器完全加载后才初始化 compat，避开所有配置/注册阶段。
 */
@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public class ForgeBusEvents {

    private static boolean compatInitialized = false;

    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (!compatInitialized) {
            compatInitialized = true;
            // 此时所有 mod、配置、资源均已完成加载
            CompatHandler.init(null);
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

    @SubscribeEvent
    public static void onShearZhajiangMaid(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide) return;
        ItemStack shears = event.getItemStack();
        if (!shears.is(Items.SHEARS)) return;
        if (!YsmCompat.isZhajiangMaid(event.getTarget())) return;

        Player player = event.getEntity();
        ItemStack stockings = new ItemStack(ModItems.STOCKINGS.get());
        if (!player.getInventory().add(stockings)) {
            player.drop(stockings, false);
        }
        if (!player.getAbilities().instabuild) {
            shears.hurtAndBreak(1, player, broken -> broken.broadcastBreakEvent(event.getHand()));
        }
        player.sendSystemMessage(Component.translatable("message.endsustain.stockings_obtained"));
        event.setCancellationResult(InteractionResult.SUCCESS);
        event.setCanceled(true);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public static void giveFinaleDropsToPlayer(LivingDropsEvent event) {
        if (!(event.getEntity() instanceof FinaleEndsustainEntity boss)) return;

        ServerPlayer recipient = event.getSource().getEntity() instanceof ServerPlayer sourcePlayer
                ? sourcePlayer
                : boss.getLastHurtByMob() instanceof ServerPlayer lastPlayer ? lastPlayer : null;
        if (recipient == null) return;

        net.minecraft.world.item.Item halo = ForgeRegistries.ITEMS.getValue(
                new net.minecraft.resources.ResourceLocation("goety_revelation", "halo_of_the_end"));

        // 从地面掉落集合中移除专属奖励，避免爆炸销毁和重复发放。
        event.getDrops().removeIf(drop -> isFinaleExclusiveDrop(drop.getItem(), halo));

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
                || enchantments.getOrDefault(Enchantments.POWER_ARROWS, 0) == 32767;
    }

    private static void giveFinaleReward(ServerPlayer player, ItemStack reward) {
        if (!player.getInventory().add(reward)) {
            ItemEntity overflow = player.drop(reward, false);
            if (overflow != null) {
                overflow.setInvulnerable(true);
                overflow.setNoPickUpDelay();
            }
        }
    }

    @SubscribeEvent
    public static void onQunUDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof com.endsustain.entity.boss.QunUEntity qun)) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        if (!(qun.level() instanceof ServerLevel level) || qun.getOwnerBoss() == null) return;
        Entity owner = level.getEntity(qun.getOwnerBoss());
        if (owner instanceof FinaleEndsustainEntity boss) {
            boss.addSleepDamageBonus(player.getUUID());
            boss.onQunUKilled(qun.getUUID());
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onFinaleIncomingAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity) {
            event.setCanceled(true);
            return;
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

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onOverflowBladeAttackEarly(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)
                || !(event.getTarget() instanceof LivingEntity target)
                || !player.getMainHandItem().is(ModItems.ENDSUSTAIN_BLADE.get())
                || !EndsustainBladeItem.hasOverflowDamage(player)
                || target.getPersistentData().getBoolean("EndsustainTrueKillRunning")) return;
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

        if (baseDamage > Integer.MAX_VALUE) {
            TrueKillUtil.forceKill(event.getEntity(), player.damageSources().fellOutOfWorld(), player,
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
        event.getDispatcher().register(Commands.literal("endsustain_test_star_arrows")
                .requires(source -> source.hasPermission(2))
                .executes(ctx -> triggerNearestFinaleStarArrows(ctx.getSource())));
    }

    private static int triggerNearestFinaleStarArrows(net.minecraft.commands.CommandSourceStack source) {
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
            source.sendFailure(Component.literal("未找到可作为星辰之矢目标的玩家"));
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
        nearestBoss.fireStarArrowsAt(nearestPlayer);
        String targetName = nearestPlayer.getGameProfile().getName();
        source.sendSuccess(() -> Component.literal("已强制最近的落幕之终焉对最近玩家 "
                + targetName + " 释放星辰之矢"), true);
        return Command.SINGLE_SUCCESS;
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
