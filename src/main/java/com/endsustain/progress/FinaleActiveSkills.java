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
import net.minecraft.world.effect.MobEffectCategory;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.entity.living.LivingChangeTargetEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingHealEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.ProjectileImpactEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
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
import java.util.UUID;

@Mod.EventBusSubscriber(modid = EndSustain.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class FinaleActiveSkills {
    public static final String DODGE_UNTIL = "EndsustainApocalypseDodgeUntil";
    private static final String DEATH_ARROW_ID = "goety:death_arrow";
    private static final String HELLFIRE_ID = "goety:hellfire";
    private static final String FIRE_TORNADO_ID = "goety:fire_tornado";
    private static final String PILLAR_ID = "goety:obsidian_monolith";
    private static final String DEATH_ARROW_FIRE_DONE = "EndsustainDeathArrowFireDone";
    private static final String DEATH_ARROW_DEBUFF_DONE = "EndsustainDeathArrowDebuffDone";
    private static final String HEAL_BLOCK_UNTIL = "EndsustainDeathArrowHealBlockUntil";
    private static final String PILLAR_OWNER = "EndsustainPillarOwner";
    private static final String PILLAR_ANCHOR = "EndsustainPillarAnchor";
    private static final String PILLAR_HEALTH_SNAPSHOT = "EndsustainPillarHealthSnapshot";
    private static final String PILLAR_PROTECTED = "EndsustainPillarProtected";
    private static final int PILLAR_LIFESPAN = 20 * 60;
    private static final double APOCALYPSE_DOMAIN_RADIUS = 40.0D;
    private static final int APOCALYPSE_ARROW_LIMIT = 20;
    private static final int APOCALYPSE_TRACKING_TICKS = 80;
    private static final String COOLDOWN_PREFIX = "EndsustainFinaleSkillCooldown";
    private static final String TIDAL_TENTACLES_ENABLED = "EndsustainTidalTentaclesEnabled";
    private static final String ABYSS_EFFECTS = "EndsustainAbyssEffects";
    private static final long[] COOLDOWNS = {50_000L, 300_000L, 50_000L, 100_000L, 120_000L, 600_000L};
    private static final List<PendingThorn> PENDING_THORNS = new ArrayList<>();
    private static final List<PendingDomainArrow> PENDING_DOMAIN_ARROWS = new ArrayList<>();
    private static final java.util.Map<java.util.UUID, CachedPillarProtection> PILLAR_PROTECTION_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

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

    @SubscribeEvent(priority = EventPriority.NORMAL)
    public static void onDeathArrowHit(LivingAttackEvent event) {
        Entity direct = event.getSource().getDirectEntity();
        if (!(direct instanceof Projectile projectile) || !isEntity(projectile, DEATH_ARROW_ID)) return;
        if (event.isCanceled()) return;
        LivingEntity target = event.getEntity();
        if (target instanceof ServerPlayer player && hasPillarProtection(player)) return;
        LivingEntity owner = projectile.getOwner() instanceof LivingEntity living ? living : null;
        applyDeathArrowImpact(projectile, target.position(), owner, target);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onDeathArrowImpact(ProjectileImpactEvent event) {
        Projectile projectile = event.getProjectile();
        if (!isEntity(projectile, DEATH_ARROW_ID) || projectile.level().isClientSide) return;
        LivingEntity owner = projectile.getOwner() instanceof LivingEntity living ? living : null;
        LivingEntity hitTarget = null;
        if (event.getRayTraceResult() instanceof EntityHitResult entityHit
                && entityHit.getEntity() instanceof LivingEntity livingTarget) {
            hitTarget = livingTarget;
        }
        applyDeathArrowImpact(projectile, event.getRayTraceResult().getLocation(), owner, hitTarget);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPillarProtectedAttack(LivingAttackEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasPillarProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPillarProtectedHurt(LivingHurtEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasPillarProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onPillarProtectedDamage(LivingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && hasPillarProtection(player)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST, receiveCanceled = true)
    public static void onDeathArrowHealingBlocked(LivingHealEvent event) {
        LivingEntity target = event.getEntity();
        if (target.level().getGameTime() < target.getPersistentData().getLong(HEAL_BLOCK_UNTIL)) {
            event.setCanceled(true);
        }
    }

    private static void applyDeathArrowImpact(Projectile projectile, Vec3 position,
                                              LivingEntity owner, LivingEntity hitTarget) {
        if (hitTarget != null && !projectile.getPersistentData().getBoolean(DEATH_ARROW_DEBUFF_DONE)) {
            projectile.getPersistentData().putBoolean(DEATH_ARROW_DEBUFF_DONE, true);
            for (MobEffectInstance effect : new ArrayList<>(hitTarget.getActiveEffects())) {
                if (effect.getEffect().getCategory() == MobEffectCategory.BENEFICIAL) {
                    hitTarget.removeEffect(effect.getEffect());
                }
            }
            hitTarget.getPersistentData().putLong(
                    HEAL_BLOCK_UNTIL,
                    hitTarget.level().getGameTime() + 20L * 15L);
        }
        if (projectile.getPersistentData().getBoolean(DEATH_ARROW_FIRE_DONE)) return;
        projectile.getPersistentData().putBoolean(DEATH_ARROW_FIRE_DONE, true);
        if (!(projectile.level() instanceof ServerLevel level)) return;
        spawnDeathArrowFire(level, position, owner, hitTarget);
    }

    private static void spawnDeathArrowFire(ServerLevel level, Vec3 position,
                                             LivingEntity owner, LivingEntity target) {
        Entity hellfire = create(HELLFIRE_ID, level);
        if (hellfire != null) {
            hellfire.setPos(position.x, position.y, position.z);
            invoke(hellfire, "setOwner", new Class[]{LivingEntity.class}, owner);
            invoke(hellfire, "setExtraDamage", new Class[]{float.class},
                    owner == null ? 0.0F : Math.max(4.0F, (float) owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * 0.5F));
            level.addFreshEntity(hellfire);
        }

        Entity tornado = create(FIRE_TORNADO_ID, level);
        if (tornado != null) {
            tornado.setPos(position.x, position.y, position.z);
            invoke(tornado, "setOwner", new Class[]{LivingEntity.class}, owner);
            invoke(tornado, "setTarget", new Class[]{LivingEntity.class}, target);
            invoke(tornado, "setDamage", new Class[]{float.class},
                    owner == null ? 7.0F : Math.max(7.0F, (float) owner.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * 0.75F));
            invoke(tornado, "setSize", new Class[]{float.class}, 2.0F);
            invoke(tornado, "setLifespan", new Class[]{int.class}, 200);
            level.addFreshEntity(tornado);
        }
        level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                position.x, position.y + 0.5D, position.z,
                36, 0.6D, 0.5D, 0.6D, 0.08D);
    }

    private static boolean isEntity(Entity entity, String id) {
        ResourceLocation typeId = entity == null ? null : ForgeRegistries.ENTITY_TYPES.getKey(entity.getType());
        return typeId != null && typeId.toString().equals(id);
    }

    @SubscribeEvent
    public static void onPillarProtectedPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || event.player.level().isClientSide
                || !(event.player instanceof ServerPlayer player)) return;
        if (hasPillarProtection(player)) {
            if (!player.getPersistentData().getBoolean(PILLAR_PROTECTED)) {
                player.getPersistentData().putFloat(PILLAR_HEALTH_SNAPSHOT, player.getHealth());
                player.getPersistentData().putBoolean(PILLAR_PROTECTED, true);
            }
            float snapshot = player.getPersistentData().getFloat(PILLAR_HEALTH_SNAPSHOT);
            if (Math.abs(player.getHealth() - snapshot) > 0.0001F) {
                player.setHealth(snapshot);
            }
        } else if (player.getPersistentData().getBoolean(PILLAR_PROTECTED)) {
            player.getPersistentData().remove(PILLAR_HEALTH_SNAPSHOT);
            player.getPersistentData().remove(PILLAR_PROTECTED);
        }
    }

    private static boolean hasPillarProtection(ServerPlayer player) {
        long now = player.level().getGameTime();
        CachedPillarProtection cached = PILLAR_PROTECTION_CACHE.get(player.getUUID());
        if (cached != null && cached.expireTick > now) return cached.value;
        UUID owner = player.getUUID();
        boolean result = player.serverLevel().getEntitiesOfClass(Entity.class,
                player.getBoundingBox().inflate(12.0D), entity ->
                        entity.isAlive()
                                && isEntity(entity, PILLAR_ID)
                                && entity.getPersistentData().hasUUID(PILLAR_OWNER)
                                && owner.equals(entity.getPersistentData().getUUID(PILLAR_OWNER))
                                && entity.getPersistentData().getBoolean(PILLAR_ANCHOR)).size() >= 1;
        PILLAR_PROTECTION_CACHE.put(player.getUUID(), new CachedPillarProtection(result, now + 5L));
        return result;
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
            setter.invoke(data, accessor, Float.valueOf(value));
        } catch (Throwable exception) {
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
        ServerLevel level = player.serverLevel();
        List<LivingEntity> targets = level.getEntitiesOfClass(LivingEntity.class,
                        player.getBoundingBox().inflate(APOCALYPSE_DOMAIN_RADIUS),
                        target -> target.isAlive()
                                && !target.isRemoved()
                                && target != player
                                && (target instanceof ServerPlayer || target instanceof net.minecraft.world.entity.monster.Enemy)
                                && !(target instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity))
                .stream()
                .sorted(Comparator.comparingDouble(target -> target.distanceToSqr(player)))
                .limit(APOCALYPSE_ARROW_LIMIT)
                .toList();
        if (targets.isEmpty()) {
            message(player, "message.endsustain.skill.no_target");
            return false;
        }

        int spawned = 0;
        for (LivingEntity target : targets) {
            Entity entity = create(DEATH_ARROW_ID, level);
            if (!(entity instanceof AbstractArrow arrow)) continue;
            double spreadX = (player.getRandom().nextDouble() - 0.5D) * 4.0D;
            double spreadZ = (player.getRandom().nextDouble() - 0.5D) * 4.0D;
            double skyY = Math.min(level.getMaxBuildHeight() - 3.0D,
                    target.getY() + 16.0D + player.getRandom().nextDouble() * 6.0D);
            Vec3 origin = new Vec3(target.getX() + spreadX, skyY, target.getZ() + spreadZ);
            arrow.setOwner(player);
            arrow.setPos(origin.x, origin.y, origin.z);
            Vec3 aim = target.getEyePosition().subtract(origin);
            arrow.shoot(aim.x, aim.y, aim.z, 2.8F, 0.0F);
            arrow.getPersistentData().putBoolean("EndsustainApocalypseDomainArrow", true);
            arrow.getPersistentData().putUUID("EndsustainApocalypseTarget", target.getUUID());
            if (level.addFreshEntity(arrow)) {
                spawned++;
                PENDING_DOMAIN_ARROWS.add(new PendingDomainArrow(level.dimension().location(),
                        arrow.getUUID(), target.getUUID(), level.getGameTime() + APOCALYPSE_TRACKING_TICKS));
                level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        target.getX(), target.getY() + 0.15D, target.getZ(),
                        20, 0.45D, 0.05D, 0.45D, 0.03D);
                level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                        origin.x, origin.y, origin.z, 12, 0.25D, 0.4D, 0.25D, 0.02D);
            }
        }
        if (spawned == 0) return false;
        renderApocalypseDomain(level, player);
        spawnObsidianPillars(player, level);
        player.getPersistentData().putLong(DODGE_UNTIL, System.currentTimeMillis() + 60_000L);
        level.playSound(null, player.blockPosition(), SoundEvents.END_PORTAL_SPAWN,
                SoundSource.PLAYERS, 1.4F, 0.7F);
        return true;
    }

    private static void renderApocalypseDomain(ServerLevel level, ServerPlayer player) {
        for (int i = 0; i < 96; i++) {
            double angle = Math.PI * 2.0D * i / 96.0D;
            double x = player.getX() + Math.cos(angle) * APOCALYPSE_DOMAIN_RADIUS;
            double z = player.getZ() + Math.sin(angle) * APOCALYPSE_DOMAIN_RADIUS;
            level.sendParticles(i % 3 == 0 ? ParticleTypes.SOUL_FIRE_FLAME : ParticleTypes.REVERSE_PORTAL,
                    x, player.getY() + 0.2D, z, 1, 0.0D, 0.08D, 0.0D, 0.0D);
        }
        level.sendParticles(ParticleTypes.REVERSE_PORTAL,
                player.getX(), player.getY() + 1.0D, player.getZ(),
                90, 2.0D, 1.2D, 2.0D, 0.08D);
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onApocalypseDodge(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) return;
        long until = player.getPersistentData().getLong(DODGE_UNTIL);
        if (until <= System.currentTimeMillis()
                || event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)
                || player.getRandom().nextFloat() >= 0.5F) return;
        event.setCanceled(true);
        teleportDodge(player);
    }

    @SubscribeEvent
    public static void onPlayerLogout(net.minecraftforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        PILLAR_PROTECTION_CACHE.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        var server = event.getServer();
        if (!PENDING_THORNS.isEmpty()) {
            PENDING_THORNS.removeIf(thorn -> {
                ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, thorn.dimension));
                if (level == null) return true;
                if (level.getGameTime() < thorn.dueTick) return false;
                com.endsustain.network.EndSustainNetwork.sendThornSpikes(
                        level, java.util.List.of(thorn.position));
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
        if (!PENDING_DOMAIN_ARROWS.isEmpty()) {
            PENDING_DOMAIN_ARROWS.removeIf(tracking -> {
                ServerLevel level = server.getLevel(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.DIMENSION, tracking.dimension));
                if (level == null || level.getGameTime() > tracking.expireTick) return true;
                Entity arrowEntity = level.getEntity(tracking.arrow);
                Entity targetEntity = level.getEntity(tracking.target);
                if (!(arrowEntity instanceof AbstractArrow arrow)
                        || !(targetEntity instanceof LivingEntity target)
                        || arrow.isRemoved()                         || target.isRemoved() || !target.isAlive()) return true;
                Vec3 desired = target.getEyePosition().subtract(arrow.position());
                if (desired.lengthSqr() < 0.25D) return false;
                Vec3 current = arrow.getDeltaMovement();
                double speed = Math.max(2.8D, current.length());
                Vec3 currentDirection = current.lengthSqr() < 1.0E-6D
                        ? desired.normalize() : current.normalize();
                Vec3 nextDirection = currentDirection.scale(0.62D)
                        .add(desired.normalize().scale(0.38D)).normalize();
                arrow.setDeltaMovement(nextDirection.scale(speed));
                arrow.hurtMarked = true;
                if (arrow.tickCount % 3 == 0) {
                    level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                            arrow.getX(), arrow.getY(), arrow.getZ(),
                            2, 0.06D, 0.06D, 0.06D, 0.0D);
                }
                return false;
            });
        }
    }

    private static void spawnObsidianPillars(ServerPlayer player, ServerLevel level) {
        player.getPersistentData().putFloat(PILLAR_HEALTH_SNAPSHOT, player.getHealth());
        player.getPersistentData().putBoolean(PILLAR_PROTECTED, true);
        int[][] offsets = {{5, 0}, {-5, 0}, {0, 5}, {0, -5}};
        for (int[] offset : offsets) {
            BlockPos start = BlockPos.containing(player.getX() + offset[0], player.getY() + 4.0D,
                    player.getZ() + offset[1]);
            BlockPos ground = findGround(level, start);
            Entity pillar = create(PILLAR_ID, level);
            if (pillar == null) continue;
            pillar.setPos(ground.getX() + 0.5D, ground.getY() + 1.0D, ground.getZ() + 0.5D);
            invoke(pillar, "setOwnerId", new Class[]{UUID.class}, player.getUUID());
            invoke(pillar, "setOwnerClientId", new Class[]{int.class}, player.getId());
            invoke(pillar, "setHostile", new Class[]{boolean.class}, false);
            invoke(pillar, "setNatural", new Class[]{boolean.class}, false);
            invoke(pillar, "setHasLifespan", new Class[]{boolean.class}, true);
            invoke(pillar, "setLifespan", new Class[]{int.class}, PILLAR_LIFESPAN);
            pillar.getPersistentData().putUUID(PILLAR_OWNER, player.getUUID());
            pillar.getPersistentData().putBoolean(PILLAR_ANCHOR, true);
            level.addFreshEntity(pillar);
        }
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
            Class<?> type = field.getType();
            if (type == float.class || type == Float.class) {
                field.setFloat(target, ((Number) value).floatValue());
            } else if (type == double.class || type == Double.class) {
                field.setDouble(target, ((Number) value).doubleValue());
            } else if (type == int.class || type == Integer.class) {
                field.setInt(target, ((Number) value).intValue());
            } else if (type == long.class || type == Long.class) {
                field.setLong(target, ((Number) value).longValue());
            } else if (type == short.class || type == Short.class) {
                field.setShort(target, ((Number) value).shortValue());
            } else if (type == byte.class || type == Byte.class) {
                field.setByte(target, ((Number) value).byteValue());
            } else if (type == boolean.class || type == Boolean.class) {
                field.setBoolean(target, value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value)));
            } else if (type == char.class || type == Character.class) {
                field.setChar(target, value instanceof Character c ? c : String.valueOf(value).isEmpty() ? '\0' : String.valueOf(value).charAt(0));
            } else {
                field.set(target, value);
            }
        } catch (Throwable ignored) {}
    }

    private static void message(ServerPlayer player, String key) {
        player.displayClientMessage(Component.translatable(key), true);
    }

    private record PendingThorn(ResourceLocation dimension, BlockPos position, java.util.UUID owner, long dueTick) {}
    private record PendingDomainArrow(ResourceLocation dimension, UUID arrow, UUID target, long expireTick) {}
    private record CachedPillarProtection(boolean value, long expireTick) {}
}
