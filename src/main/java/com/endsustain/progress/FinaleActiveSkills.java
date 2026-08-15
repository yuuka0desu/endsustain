package com.endsustain.progress;

import com.endsustain.EndSustain;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.OwnableEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FinaleActiveSkills {
    public static final String DODGE_UNTIL = "EndsustainApocalypseDodgeUntil";
    private static final String COOLDOWN_PREFIX = "EndsustainFinaleSkillCooldown";
    private static final String TIDAL_TENTACLES_ENABLED = "EndsustainTidalTentaclesEnabled";
    private static final String ABYSS_EFFECTS = "EndsustainAbyssEffects";
    private static final long[] COOLDOWNS = {50_000L, 300_000L, 50_000L, 100_000L, 120_000L, 600_000L};
    private static final List<PendingThorn> PENDING_THORNS = new ArrayList<>();

    private FinaleActiveSkills() {}

    public static void trigger(ServerPlayer player, int skill) {
        if (skill < 0 || skill >= 6 || !player.isAlive()) return;
        FinalePathProgress.scanInventory(player);
        migrateSkillCooldowns(player);
        if (!FinalePathProgress.isWearingSmallZhanjiang(player)) {
            message(player, "message.endsustain.skill.requires_guide");
            return;
        }
        if (player.getPersistentData().getInt(FinalePathProgress.TIER) <= skill) {
            message(player, "message.endsustain.skill.locked");
            return;
        }
        long now = System.currentTimeMillis();
        long until = player.getPersistentData().getLong(COOLDOWN_PREFIX + skill);
        if (until > now) {
            player.displayClientMessage(Component.translatable("message.endsustain.skill.cooldown", (until - now + 999L) / 1000L), true);
            return;
        }

        boolean success = switch (skill) {
            case 0 -> voidThorns(player);
            case 1 -> phantomServants(player);
            case 2 -> harbingerLaser(player);
            case 3 -> tidalHook(player);
            case 4 -> flameStrike(player);
            case 5 -> apocalypseRing(player);
            default -> false;
        };
        if (success) {
            player.getPersistentData().putLong(COOLDOWN_PREFIX + skill, now + COOLDOWNS[skill]);
            player.displayClientMessage(Component.translatable("message.endsustain.skill.cast." + skill), true);
        } else message(player, "message.endsustain.skill.failed");
    }

    private static void migrateSkillCooldowns(ServerPlayer player) {
        String versionKey = "EndsustainFinaleSkillCooldownLayoutVersion";
        if (player.getPersistentData().getInt(versionKey) >= 2) return;
        // 旧顺序 -> 新顺序：旧 [先驱,深渊,余烬,魂火,虚空,天启] 到新 [虚空,魂火,先驱,深渊,余烬,天启]。
        int[] oldIndexForNew = {4, 3, 0, 1, 2, 5};
        long[] old = new long[6];
        for (int i = 0; i < 6; i++) old[i] = player.getPersistentData().getLong(COOLDOWN_PREFIX + i);
        for (int i = 0; i < oldIndexForNew.length; i++) player.getPersistentData().putLong(COOLDOWN_PREFIX + i, old[oldIndexForNew[i]]);
        player.getPersistentData().putInt(versionKey, 2);
    }

    public static void toggleTidalTentacles(ServerPlayer player) {
        FinalePathProgress.scanInventory(player);
        if (!FinalePathProgress.isWearingSmallZhanjiang(player)
                || player.getPersistentData().getInt(FinalePathProgress.TIER) < 4) {
            message(player, "message.endsustain.skill.locked");
            return;
        }
        boolean enabled = !player.getPersistentData().getBoolean(TIDAL_TENTACLES_ENABLED);
        player.getPersistentData().putBoolean(TIDAL_TENTACLES_ENABLED, enabled);
        player.displayClientMessage(Component.translatable(enabled
                ? "message.endsustain.skill.tidal_enabled" : "message.endsustain.skill.tidal_disabled"), true);
    }

    @SubscribeEvent
    public static void onTidalAttack(LivingAttackEvent event) {
        Entity directSource = event.getSource().getDirectEntity();
        ResourceLocation directId = directSource == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(directSource.getType());
        if (directId != null && directId.equals(new ResourceLocation("cataclysm", "tidal_tentacle"))) return;
        if (!(event.getSource().getEntity() instanceof ServerPlayer player)
                || event.getEntity() == player || event.isCanceled()
                || !player.getPersistentData().getBoolean(TIDAL_TENTACLES_ENABLED)
                || player.getPersistentData().getInt(FinalePathProgress.TIER) < 4
                || !(player.level() instanceof ServerLevel level)) return;
        LivingEntity target = event.getEntity();
        if (!validTarget(player, target)) return;
        spawnFixedTidalTentacles(level, player, target,
                (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE));
    }

    private static void spawnFixedTidalTentacles(ServerLevel level, ServerPlayer player, LivingEntity target, float damage) {
        for (int i = 0; i < 3; i++) {
            Entity tentacle = create("cataclysm:tidal_tentacle", level);
            if (tentacle == null) continue;
            invoke(tentacle, "setCreatorEntityUUID", new Class[]{java.util.UUID.class}, player.getUUID());
            invoke(tentacle, "setFromEntityID", new Class[]{int.class}, player.getId());
            invoke(tentacle, "setToEntityID", new Class[]{int.class}, target.getId());
            // 达到原版链式触手上限，触手完成首段攻击后直接回收，不会换锁其他目标。
            invoke(tentacle, "setTargetsHit", new Class[]{int.class}, 6);
            invoke(tentacle, "setProgress", new Class[]{float.class}, 0.0F);
            setSynchedFloat(tentacle, "DAMAGE", damage);
            tentacle.getPersistentData().putBoolean("EndsustainFixedTidalTarget", true);
            tentacle.getPersistentData().putUUID("EndsustainTidalTarget", target.getUUID());
            tentacle.setPos(player.getX(), player.getY() + 0.4D + i * 0.25D, player.getZ());
            level.addFreshEntity(tentacle);
        }
    }

    @SubscribeEvent
    public static void onDeeplingTarget(LivingChangeTargetEvent event) {
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(event.getEntity().getType());
        if (id != null && id.equals(new ResourceLocation("cataclysm", "deepling"))
                && event.getNewTarget() instanceof ServerPlayer) event.setNewTarget(null);
    }

    @SubscribeEvent
    public static void onAbyssPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide
                || !(event.player instanceof ServerPlayer player)) return;
        boolean active = player.getPersistentData().getBoolean("SmallZhanjiangActive")
                && player.getPersistentData().getInt(FinalePathProgress.TIER) >= 4;
        if (!active) {
            player.getPersistentData().putBoolean(TIDAL_TENTACLES_ENABLED, false);
            if (player.getPersistentData().getBoolean(ABYSS_EFFECTS)) {
                MobEffectInstance breathing = player.getEffect(MobEffects.WATER_BREATHING);
                if (breathing != null && breathing.getAmplifier() == 0 && !breathing.isVisible()) player.removeEffect(MobEffects.WATER_BREATHING);
                MobEffectInstance regeneration = player.getEffect(MobEffects.REGENERATION);
                if (regeneration != null && regeneration.getAmplifier() == 1 && !regeneration.isVisible()) player.removeEffect(MobEffects.REGENERATION);
                player.getPersistentData().putBoolean(ABYSS_EFFECTS, false);
            }
            return;
        }
        player.addEffect(new MobEffectInstance(MobEffects.WATER_BREATHING, Integer.MAX_VALUE, 0, false, false, true));
        if (!player.isUnderWater() && !player.isInWaterRainOrBubble()) {
            player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, Integer.MAX_VALUE, 1, false, false, true));
        } else {
            MobEffectInstance regeneration = player.getEffect(MobEffects.REGENERATION);
            if (regeneration != null && regeneration.getAmplifier() == 1 && !regeneration.isVisible()) player.removeEffect(MobEffects.REGENERATION);
        }
        player.getPersistentData().putBoolean(ABYSS_EFFECTS, true);
    }

    private static void setSynchedFloat(Entity entity, String fieldName, float value) {
        try {
            Field field = entity.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object accessor = field.get(null);
            Object data = entity.getEntityData();
            Method setter;
            try {
                setter = data.getClass().getMethod("set", net.minecraft.network.syncher.EntityDataAccessor.class, Object.class);
            } catch (NoSuchMethodException ignored) {
                setter = data.getClass().getMethod("m_135381_", net.minecraft.network.syncher.EntityDataAccessor.class, Object.class);
            }
            setter.invoke(data, accessor, value);
        } catch (ReflectiveOperationException exception) {
            EndSustain.LOGGER.warn("无法设置潮汐触手伤害快照", exception);
        }
    }

    private static boolean harbingerLaser(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 start = player.getEyePosition().add(player.getLookAngle().scale(0.6D));
        Vec3 look = player.getLookAngle().normalize();
        Vec3 end = start.add(look.scale(64.0D));
        Entity visual = create("cataclysm:laser_beam", level);
        if (visual instanceof Projectile projectile) {
            projectile.setOwner(player);
            visual.setPos(start.x, start.y, start.z);
            visual.setDeltaMovement(look.scale(1.6D));
            setField(visual, "xPower", look.x * 0.1D);
            setField(visual, "yPower", look.y * 0.1D);
            setField(visual, "zPower", look.z * 0.1D);
            invoke(visual, "setDamage", new Class[]{float.class}, 0.0F);
            level.addFreshEntity(visual);
        }
        AABB corridor = new AABB(start, end).inflate(1.25D);
        List<LivingEntity> hits = level.getEntitiesOfClass(LivingEntity.class, corridor,
                target -> validTarget(player, target) && distanceToRay(start, end, target.getBoundingBox().getCenter()) <= 1.35D + target.getBbWidth() * 0.5D);
        hits.sort(Comparator.comparingDouble(target -> target.distanceToSqr(player)));
        float damage = (float) player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        for (LivingEntity target : hits) {
            target.invulnerableTime = 0;
            if (target.hurt(level.damageSources().playerAttack(player), damage)) {
                spawnHarbingerCrossfire(level, player, target, damage);
            }
        }
        level.playSound(null, player.blockPosition(), sound("cataclysm:harbinger_laser"), SoundSource.PLAYERS, 1.5F, 1.0F);
        return visual != null || !hits.isEmpty();
    }

    private static void spawnHarbingerCrossfire(ServerLevel level, ServerPlayer player, LivingEntity target, float damage) {
        // 立方体八条体对角线：全部 (±1, ±1, ±1) 组合。
        for (int sx : new int[]{-1, 1}) for (int sy : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            Vec3 direction = new Vec3(sx, sy, sz).normalize();
            Entity beam = create("cataclysm:laser_beam", level);
            if (beam instanceof Projectile projectile) {
                projectile.setOwner(player);
                beam.addTag("endsustain_harbinger_crossfire");
                beam.getPersistentData().putUUID("EndsustainCrossfireTarget", target.getUUID());
                beam.setPos(target.getX(), target.getY() + target.getBbHeight() * 0.5D, target.getZ());
                beam.setDeltaMovement(direction.scale(1.6D));
                setField(beam, "xPower", direction.x * 0.1D);
                setField(beam, "yPower", direction.y * 0.1D);
                setField(beam, "zPower", direction.z * 0.1D);
                invoke(beam, "setDamage", new Class[]{float.class}, 0.0F);
                level.addFreshEntity(beam);
            }
            target.invulnerableTime = 0;
            target.hurt(level.damageSources().playerAttack(player), damage);
        }
        level.sendParticles(ParticleTypes.END_ROD, target.getX(), target.getY() + target.getBbHeight() * 0.5D,
                target.getZ(), 48, 0.35D, 0.35D, 0.35D, 0.08D);
    }

    private static boolean tidalHook(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Entity hook = create("cataclysm:tidal_hook", level);
        if (!(hook instanceof AbstractArrow arrow)) return false;
        arrow.setOwner(player);
        arrow.setPos(player.getX(), player.getEyeY() - 0.1D, player.getZ());
        Item claws = ForgeRegistries.ITEMS.getValue(new ResourceLocation("cataclysm:tidal_claws"));
        ItemStack stack = claws == null ? ItemStack.EMPTY : new ItemStack(claws);
        boolean initialized = invoke(hook, "setProperties",
                new Class[]{ItemStack.class, double.class, double.class, float.class, float.class, float.class, float.class},
                stack, 30.0D, 12.0D, player.getXRot(), player.getYRot(), 0.0F, 1.8F);
        if (!initialized) arrow.shootFromRotation(player, player.getXRot(), player.getYRot(), 0.0F, 1.8F, 0.0F);
        hook.getPersistentData().putBoolean("EndsustainFinaleTidalHook", true);
        if (!setCataclysmHookActive(player, true)) return false;
        boolean added = level.addFreshEntity(hook);
        if (!added) setCataclysmHookActive(player, false);
        return added;
    }

    private static boolean flameStrike(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        Vec3 look = player.getLookAngle();
        Vec3 horizontal = new Vec3(look.x, 0.0D, look.z);
        if (horizontal.lengthSqr() < 0.01D) horizontal = Vec3.directionFromRotation(0.0F, player.getYRot());
        horizontal = horizontal.normalize();
        Vec3 desired = player.position().add(horizontal.scale(7.0D));
        BlockPos ground = findGround(level, BlockPos.containing(desired.x, player.getY() + 4.0D, desired.z));
        Entity strike = constructFlameStrike(level, ground.getX() + 0.5D, ground.getY() + 1.0D, ground.getZ() + 0.5D, player);
        if (strike == null) return false;
        return level.addFreshEntity(strike);
    }

    private static Entity constructFlameStrike(ServerLevel level, double x, double y, double z, ServerPlayer owner) {
        try {
            Class<?> type = Class.forName("com.github.L_Ender.cataclysm.entity.effect.Flame_Strike_Entity");
            Constructor<?> constructor = type.getConstructor(net.minecraft.world.level.Level.class, double.class, double.class, double.class,
                    float.class, int.class, int.class, int.class, float.class, float.class, float.class, boolean.class, LivingEntity.class);
            return (Entity) constructor.newInstance(level, x, y, z, 3.5F, 40, 10, 8, 12.0F, 0.08F, 1.0F, false, owner);
        } catch (ReflectiveOperationException exception) {
            EndSustain.LOGGER.error("创建灾变烈焰轰击失败", exception);
            return null;
        }
    }

    private static boolean phantomServants(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        int spawned = 0;
        for (int i = 0; i < 3; i++) {
            Entity phantom = create("goety:phantom_servant", level);
            if (!(phantom instanceof Mob mob)) continue;
            double angle = Math.PI * 2.0D * i / 3.0D;
            mob.moveTo(player.getX() + Math.cos(angle) * 2.0D, player.getY() + 3.0D, player.getZ() + Math.sin(angle) * 2.0D,
                    player.getYRot(), 0.0F);
            invoke(phantom, "setOwnerId", new Class[]{java.util.UUID.class}, player.getUUID());
            invoke(phantom, "setOwnerClientId", new Class[]{int.class}, player.getId());
            invoke(phantom, "setHasLifespan", new Class[]{boolean.class}, true);
            invoke(phantom, "setLifespan", new Class[]{int.class}, 3600);
            mob.setTarget(nearestEnemy(player, 32.0D));
            if (level.addFreshEntity(mob)) spawned++;
        }
        return spawned == 3;
    }

    private static boolean voidThorns(ServerPlayer player) {
        LivingEntity target = nearestEnemy(player, 32.0D);
        if (target == null) {
            message(player, "message.endsustain.skill.no_target");
            return false;
        }
        ServerLevel level = player.serverLevel();
        long due = level.getGameTime() + 20L;
        Vec3 direction = target.getDeltaMovement();
        Vec3 center = target.position().add(direction.x * 10.0D, 0.0D, direction.z * 10.0D);
        for (int i = -1; i <= 1; i++) {
            double angle = Math.atan2(target.getZ() - player.getZ(), target.getX() - player.getX()) + Math.PI / 2.0D;
            Vec3 position = center.add(Math.cos(angle) * i * 1.4D, 0.0D, Math.sin(angle) * i * 1.4D);
            BlockPos ground = findGround(level, BlockPos.containing(position.x, target.getY() + 4.0D, position.z));
            PENDING_THORNS.add(new PendingThorn(level.dimension().location(), ground.above(), player.getUUID(), due));
            level.sendParticles(ParticleTypes.PORTAL, ground.getX() + 0.5D, ground.getY() + 1.1D, ground.getZ() + 0.5D, 18, 0.35D, 0.1D, 0.35D, 0.03D);
        }
        level.playSound(null, target.blockPosition(), sound("bosses_of_mass_destruction:spike_indicator"), SoundSource.PLAYERS, 1.2F, 1.0F);
        return true;
    }

    private static boolean apocalypseRing(ServerPlayer player) {
        LivingEntity target = nearestEnemy(player, 40.0D);
        if (target == null) {
            message(player, "message.endsustain.skill.no_target");
            return false;
        }
        ServerLevel level = player.serverLevel();
        int spawned = 0;
        for (int i = 0; i < 10; i++) {
            Entity entity = create("goety:death_arrow", level);
            if (!(entity instanceof AbstractArrow arrow)) continue;
            double angle = Math.PI * 2.0D * i / 10.0D;
            Vec3 origin = player.position().add(Math.cos(angle) * 2.4D, 1.2D + (i % 2) * 0.7D, Math.sin(angle) * 2.4D);
            arrow.setOwner(player);
            arrow.setPos(origin.x, origin.y, origin.z);
            Vec3 aim = target.getEyePosition().subtract(origin);
            arrow.shoot(aim.x, aim.y, aim.z, 2.2F, 1.5F);
            if (level.addFreshEntity(arrow)) spawned++;
        }
        if (spawned == 0) return false;
        player.getPersistentData().putLong(DODGE_UNTIL, System.currentTimeMillis() + 60_000L);
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME, player.getX(), player.getY() + 1.0D, player.getZ(), 60, 1.2D, 1.0D, 1.2D, 0.04D);
        return true;
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onApocalypseDodge(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) return;
        long until = player.getPersistentData().getLong(DODGE_UNTIL);
        if (until <= System.currentTimeMillis() || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) return;
        event.setCanceled(true);
        teleportDodge(player);
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_THORNS.isEmpty()) return;
        var server = event.getServer();
        PENDING_THORNS.removeIf(thorn -> {
            ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, thorn.dimension));
            if (level == null || level.getGameTime() < thorn.dueTick) return false;
            Entity ownerEntity = level.getEntity(thorn.owner);
            if (!(ownerEntity instanceof ServerPlayer owner)) return true;
            AABB box = new AABB(thorn.position).inflate(1.25D, 2.0D, 1.25D);
            for (LivingEntity target : level.getEntitiesOfClass(LivingEntity.class, box, target -> validTarget(owner, target))) {
                target.hurt(level.damageSources().playerAttack(owner), Math.max(10.0F,
                        (float) owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)));
                target.knockback(1.0D, owner.getX() - target.getX(), owner.getZ() - target.getZ());
            }
            level.sendParticles(ParticleTypes.REVERSE_PORTAL, thorn.position.getX() + 0.5D, thorn.position.getY() + 0.8D,
                    thorn.position.getZ() + 0.5D, 55, 0.45D, 1.1D, 0.45D, 0.08D);
            level.playSound(null, thorn.position, sound("bosses_of_mass_destruction:void_blossom_spike"), SoundSource.PLAYERS, 1.3F, 0.95F);
            return true;
        });
    }

    private static void teleportDodge(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        double oldX = player.getX(), oldY = player.getY(), oldZ = player.getZ();
        for (int i = 0; i < 16; i++) {
            double x = oldX + (player.getRandom().nextDouble() - 0.5D) * 16.0D;
            double z = oldZ + (player.getRandom().nextDouble() - 0.5D) * 16.0D;
            BlockPos ground = findGround(level, BlockPos.containing(x, oldY + 5.0D, z));
            double y = ground.getY() + 1.0D;
            AABB destination = player.getBoundingBox().move(x - oldX, y - oldY, z - oldZ);
            if (level.noCollision(player, destination) && !level.containsAnyLiquid(destination)) {
                level.sendParticles(ParticleTypes.PORTAL, oldX, oldY + 1.0D, oldZ, 32, 0.4D, 0.8D, 0.4D, 0.2D);
                player.teleportTo(level, x, y, z, player.getYRot(), player.getXRot());
                level.sendParticles(ParticleTypes.PORTAL, x, y + 1.0D, z, 32, 0.4D, 0.8D, 0.4D, 0.2D);
                level.playSound(null, BlockPos.containing(x, y, z), SoundEvents.ENDERMAN_TELEPORT, SoundSource.PLAYERS, 1.0F, 1.0F);
                return;
            }
        }
    }

    private static LivingEntity nearestEnemy(ServerPlayer player, double range) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range),
                        target -> validTarget(player, target) && (target instanceof Enemy || target instanceof Mob mob && mob.getTarget() == player))
                .stream().min(Comparator.comparingDouble(target -> target.distanceToSqr(player))).orElse(null);
    }

    private static boolean validTarget(ServerPlayer player, LivingEntity target) {
        if (!target.isAlive() || target == player || target.isAlliedTo(player) || player.isAlliedTo(target)) return false;
        if (target instanceof OwnableEntity ownable && ownable.getOwnerUUID() != null && ownable.getOwnerUUID().equals(player.getUUID())) return false;
        ResourceLocation id = ForgeRegistries.ENTITY_TYPES.getKey(target.getType());
        return id == null || !id.getNamespace().equals(EndSustain.MOD_ID) || !id.getPath().equals("small_zhanjiang_companion");
    }

    private static BlockPos findGround(ServerLevel level, BlockPos start) {
        BlockPos.MutableBlockPos cursor = start.mutable();
        for (int i = 0; i < 16 && cursor.getY() > level.getMinBuildHeight() + 1; i++, cursor.move(0, -1, 0)) {
            if (!level.getBlockState(cursor).getCollisionShape(level, cursor).isEmpty() && level.getBlockState(cursor.above()).isAir()) return cursor.immutable();
        }
        return BlockPos.containing(start.getX(), Mth.clamp(start.getY(), level.getMinBuildHeight() + 1, level.getMaxBuildHeight() - 2), start.getZ());
    }

    private static double distanceToRay(Vec3 start, Vec3 end, Vec3 point) {
        Vec3 line = end.subtract(start);
        double t = Mth.clamp(point.subtract(start).dot(line) / line.lengthSqr(), 0.0D, 1.0D);
        return point.distanceTo(start.add(line.scale(t)));
    }

    private static Entity create(String id, ServerLevel level) {
        EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(id));
        return type == null ? null : type.create(level);
    }

    private static net.minecraft.sounds.SoundEvent sound(String id) {
        net.minecraft.sounds.SoundEvent sound = ForgeRegistries.SOUND_EVENTS.getValue(new ResourceLocation(id));
        return sound == null ? SoundEvents.EVOKER_CAST_SPELL : sound;
    }

    private static boolean setCataclysmHookActive(ServerPlayer player, boolean active) {
        try {
            Class<?> capabilities = Class.forName("com.github.L_Ender.cataclysm.init.ModCapabilities");
            Class<?> capabilityType = Class.forName("net.minecraftforge.common.capabilities.Capability");
            Object hookCapability = capabilities.getField("HOOK_CAPABILITY").get(null);
            Method getter = capabilities.getMethod("getCapability", Entity.class, capabilityType);
            Object hookState = getter.invoke(null, player, hookCapability);
            if (hookState == null) return false;
            Class<?> hookInterface = Class.forName("com.github.L_Ender.cataclysm.capabilities.HookCapability$IHookCapability");
            hookInterface.getMethod("setHasHook", boolean.class).invoke(hookState, active);
            return true;
        } catch (ReflectiveOperationException exception) {
            EndSustain.LOGGER.error("设置灾变潮汐钩爪状态失败", exception);
            return false;
        }
    }

    private static boolean invoke(Object target, String name, Class<?>[] parameterTypes, Object... args) {
        try {
            Method method = target.getClass().getMethod(name, parameterTypes);
            method.invoke(target, args);
            return true;
        } catch (ReflectiveOperationException exception) {
            EndSustain.LOGGER.warn("技能兼容调用失败: {}.{}", target.getClass().getName(), name);
            return false;
        }
    }

    private static void setField(Object target, String name, Object value) {
        try {
            Field field = target.getClass().getField(name);
            field.set(target, value);
        } catch (ReflectiveOperationException ignored) {}
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private record PendingThorn(ResourceLocation dimension, BlockPos position, java.util.UUID owner, long dueTick) {}
}
