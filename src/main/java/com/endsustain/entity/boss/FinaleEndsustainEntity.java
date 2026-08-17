package com.endsustain.entity.boss;

import com.endsustain.combat.TrueKillUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.BossEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;

import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraftforge.registries.ForgeRegistries;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Boss —— 落幕之终焉·末影蘸酱 (Finale Endsustain)。<br>
 * 使用原版 PlayerModel 渲染，无第三方模组依赖。<p>
 * 三套技能组：<ol>
 *   <li><b>铁魔法</b>：黑洞 lv10 / 魔法飞弹 lv255 / 星海落瀑 lv255 /
 *       混沌黑焰 lv35 / 死烟吞噬 lv35 / 巫术湮灭射线 lv35</li>
 *   <li><b>诡厄巫法</b>：六种聚晶</li>
 *   <li><b>近战/特殊</b>：终焉之刃投掷（制导追踪+忠诚回收）；
 *       必杀技举刃→锁定冲刺→收刀→1tick 后 2147483647 真实伤害</li>
 * </ol>
 * 受伤 30% 概率瞬移到 32 格内地面。
 */
public class FinaleEndsustainEntity extends Monster implements GeoEntity {

    // ======================== GeckoLib 动画接口 ================
    private final AnimatableInstanceCache animCache = GeckoLibUtil.createInstanceCache(this);

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        AnimationController<FinaleEndsustainEntity> mainController = new AnimationController<>(this, "main", 0, state -> {
            if (isSleeping()) {
                return state.setAndContinue(RawAnimation.begin().thenLoop("animation.sleep"));
            }
            if (getAttackState() == STATE_CASTING || getAttackState() == STATE_CHARM) {
                return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("animation.cast_forward"));
            }
            if (getAttackState() == STATE_THROW_BLADE) {
                return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("animation.blade_raise"));
            }
            if (getAttackState() >= STATE_ULT_RAISE && getAttackState() <= STATE_ULT_SHEATHE) {
                return state.setAndContinue(RawAnimation.begin().thenPlayAndHold("animation.blade_raise"));
            }
            return state.setAndContinue(RawAnimation.begin().thenLoop("animation.idle"));
        });
        mainController.setAnimationSpeedHandler(entity ->
                entity.getAttackState() == STATE_CASTING || entity.getAttackState() == STATE_CHARM || entity.getAttackState() == STATE_THROW_BLADE ? 5.0D : 1.0D);
        controllers.add(mainController);
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return this.animCache; }
    @Override public double getTick(Object o) { return this.tickCount; }

    // ======================== 技能 ID ========================
    public static final String SPELL_BLACK_HOLE       = "black_hole";
    public static final String SPELL_MAGIC_MISSILE    = "magic_missile";
    public static final String SPELL_STARFALL         = "starfall";
    public static final String SPELL_CHAOS_FLAME      = "chaos_orb";
    public static final String SPELL_DEATH_SMOKE      = "devour";
    public static final String SPELL_ANNIHILATION_RAY = "ray_of_siphoning";

    public static final String CRYSTAL_REND    = "rending_crystal";
    public static final String CRYSTAL_CULT    = "cult_crystal";
    public static final String CRYSTAL_CORRUPT = "corrupting_crystal";
    public static final String CRYSTAL_WITHER  = "wither_crystal";
    public static final String CRYSTAL_BIND    = "binding_crystal";
    public static final String CRYSTAL_SHOCK   = "shocking_crystal";

    // ======================== 状态机 ========================
    public static final int STATE_IDLE         = 0;
    public static final int STATE_CASTING      = 1;   // 铁魔法 / 诡厄巫法 施法中
    public static final int STATE_THROW_BLADE  = 2;   // 投掷终焉之刃
    public static final int STATE_ULT_RAISE    = 3;
    public static final int STATE_ULT_DASH     = 4;
    public static final int STATE_ULT_SHEATHE  = 5;
    public static final int STATE_CHARM        = 6;
    private static final int ULTIMATE_COOLDOWN_TICKS = 20 * 60;
    public static final int PHASE_NEUTRAL = 0, PHASE_SLEEPING = 1, PHASE_HOSTILE = 2;
    private static final EntityDataAccessor<Integer> SOCIAL_PHASE =
            SynchedEntityData.defineId(FinaleEndsustainEntity.class, EntityDataSerializers.INT);
    private int neutralIdleTicks, neutralHurtTicks, sleepSummonTicks;
    private float observedHealth;
    private boolean hitWithoutDamage;
    private boolean sleepInvulnerable;
    private final java.util.Set<UUID> qunUIds = new java.util.HashSet<>();
    private final Map<UUID, Integer> sleepDamageBonus = new HashMap<>();

    private static final EntityDataAccessor<Integer> ATTACK_STATE =
            SynchedEntityData.defineId(FinaleEndsustainEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> ATTACK_TICK =
            SynchedEntityData.defineId(FinaleEndsustainEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> CAST_ANIMATION =
            SynchedEntityData.defineId(FinaleEndsustainEntity.class, EntityDataSerializers.INT);

    private final ServerBossEvent bossEvent = new ServerBossEvent(
            this.getDisplayName(), BossEvent.BossBarColor.PURPLE, BossEvent.BossBarOverlay.PROGRESS);

    @Nullable private Player ultTarget;
    @Nullable private Vec3   ultLockedPos;
    @Nullable private Vec3   ultEntryPos;
    @Nullable private Vec3   ultExitPos;
    private boolean  ultDamagePending;

    /** 当前正在释放的技能名（仅记录，真实接入由 compat 层完成） */
    @Nullable private String currentCastingSpell;
    @Nullable private Player magicMissileBarrageTarget;
    @Nullable private Player charmedTarget;
    private int teleportCooldown;
    private int passiveCrystalAnchorCooldown;
    private int lowHealthStarArrowCooldown;
    @Nullable private Vec3 pendingStarStrikePos;
    @Nullable private Vec3 starStrikeDomainCenter;
    private int starStrikeWindup;
    private int starStrikeImpactDelay;
    private int starStrikeDomainTicks;
    private int ultimateCooldown;

    // ======================== 构造 ========================

    public FinaleEndsustainEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.xpReward = 5000;
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH,        120000.0D)
                .add(Attributes.MOVEMENT_SPEED,         0.32D)
                .add(Attributes.ATTACK_DAMAGE,          36.0D)
                .add(Attributes.ARMOR,                  20.0D)
                .add(Attributes.ARMOR_TOUGHNESS,        12.0D)
                .add(Attributes.KNOCKBACK_RESISTANCE,   1.0D)
                .add(Attributes.FOLLOW_RANGE,           64.0D);
    }

    // ======================== synch ========================

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        this.entityData.define(SOCIAL_PHASE, PHASE_NEUTRAL);
        this.entityData.define(ATTACK_STATE, STATE_IDLE);
        this.entityData.define(ATTACK_TICK, 0);
        this.entityData.define(CAST_ANIMATION, 0);
    }

    public int getSocialPhase() { return this.entityData.get(SOCIAL_PHASE); }
    private void setSocialPhase(int phase) { this.entityData.set(SOCIAL_PHASE, phase); }
    public boolean isSleeping() { return getSocialPhase() == PHASE_SLEEPING; }
    public boolean canReceiveDamageNow() { return getSocialPhase() == PHASE_HOSTILE || !sleepInvulnerable; }
    public int getSleepDamageBonusStacks(UUID player) { return sleepDamageBonus.getOrDefault(player, 0); }
    public void addSleepDamageBonus(UUID player) { sleepDamageBonus.merge(player, 1, Integer::sum); }
    public void clearSleepDamageBonusStacks(UUID player) { sleepDamageBonus.remove(player); }
    void tickSocialPhase() { if (observedHealth <= 0.0F) observedHealth = getHealth(); FinaleEndsustainSocial.tick(this); }
    void tickSleepingSummons() {
        if (this.level() instanceof ServerLevel server && this.tickCount % 10 == 0) {
            double angle = this.tickCount * 0.18D;
            server.sendParticles(ParticleTypes.END_ROD, this.getX() + Math.cos(angle) * 0.45D,
                    this.getEyeY() + 0.25D, this.getZ() + Math.sin(angle) * 0.45D,
                    2, 0.08D, 0.06D, 0.08D, 0.01D);
            server.sendParticles(ParticleTypes.REVERSE_PORTAL, this.getX(), this.getEyeY() + 0.55D,
                    this.getZ(), 3, 0.28D, 0.12D, 0.28D, 0.01D);
            if (this.tickCount % 20 == 0) {
                double zHeight = 0.65D + (this.tickCount / 20 % 3) * 0.28D;
                server.sendParticles(ParticleTypes.CLOUD, this.getX() + 0.35D,
                        this.getEyeY() + zHeight, this.getZ(), 2, 0.04D, 0.03D, 0.04D, 0.005D);
            }
        }
        if (++sleepSummonTicks >= 200 || qunUIds.isEmpty()) {
            sleepSummonTicks = 0;
            FinaleEndsustainSocial.summon(this);
        }
    }
    public void trackQunU(UUID id) { qunUIds.add(id); }
    public boolean ownsQunU(UUID id) { return qunUIds.contains(id); }
    float getObservedHealthInternal() { return observedHealth; }
    void setObservedHealthInternal(float value) { observedHealth = value; }
    boolean hasHitWithoutDamageInternal() { return hitWithoutDamage; }
    void incrementNeutralHurtInternal() { neutralHurtTicks++; }
    int getNeutralHurtInternal() { return neutralHurtTicks; }
    int getNeutralIdleInternal() { return neutralIdleTicks; }
    int incrementNeutralIdleInternal() { return ++neutralIdleTicks; }
    void enterHostileInternal() { setSocialPhase(PHASE_HOSTILE); neutralIdleTicks = neutralHurtTicks = 0; hitWithoutDamage = false; sleepInvulnerable = false; }
    void enterSleepingInternal() { setSocialPhase(PHASE_SLEEPING); sleepInvulnerable = true; neutralIdleTicks = neutralHurtTicks = 0; sleepSummonTicks = 0; }
    public void onQunUKilled(UUID id) { qunUIds.remove(id); if (qunUIds.isEmpty()) sleepInvulnerable = false; }
    public int  getAttackState()      { return this.entityData.get(ATTACK_STATE); }
    public void setAttackState(int s) { this.entityData.set(ATTACK_STATE, s); }
    public int  getAttackTick()       { return this.entityData.get(ATTACK_TICK); }
    public void setAttackTick(int t)  { this.entityData.set(ATTACK_TICK, t); }
    public int  getCastAnimationVariant() { return this.entityData.get(CAST_ANIMATION); }
    private void randomizeCastAnimation() { this.entityData.set(CAST_ANIMATION, this.random.nextInt(3)); }

    // ======================== AI ========================

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        this.goalSelector.addGoal(1, new FinaleCombatGoal(this));
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 16.0F));
        this.goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        this.targetSelector.addGoal(1, new HurtByTargetGoal(this));
        this.targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    // ======================== Boss bar ========================

    @Override
    public void startSeenByPlayer(ServerPlayer player) {
        super.startSeenByPlayer(player);
        this.bossEvent.addPlayer(player);
        com.endsustain.progress.FinaleMilestones.award(player,
                com.endsustain.progress.FinaleMilestones.BATTLE_STARTED);
    }

    @Override
    public void stopSeenByPlayer(ServerPlayer player) {
        super.stopSeenByPlayer(player);
        this.bossEvent.removePlayer(player);
    }

    @Override
    public void aiStep() {
        super.aiStep();
        this.bossEvent.setProgress(this.getHealth() / this.getMaxHealth());
        if (!this.level().isClientSide) {
            tickSocialPhase();
            if (isSleeping()) {
                tickSleepingSummons();
                return;
            }
        }
        if (!this.level().isClientSide) {
            if (getSocialPhase() == PHASE_HOSTILE && DimensionPhaseManager.tick(this)) return;
            if (!this.getPersistentData().getBoolean("EndsustainAllChampionAffixes")
                    && com.endsustain.compat.champions.ChampionsCompat.applyAllAffixes(this)) {
                this.getPersistentData().putBoolean("EndsustainAllChampionAffixes", true);
            }
            if (this.teleportCooldown > 0) {
                this.teleportCooldown--;
            }
            if (this.ultimateCooldown > 0) {
                this.ultimateCooldown--;
            }
            suppressFlightInFollowRange();
            tickPassiveCrystalAnchorAttack();
            tickLowHealthStarArrows();
            tickStarArrowTrueKill();
            int state = getAttackState();
            if (state != STATE_IDLE && this.tickCount % 4 == 0) {
                spawnCastingAuraParticles(state);
            }
            // 施法中持续面朝目标追踪
            if (state == STATE_CASTING || state == STATE_THROW_BLADE || state == STATE_CHARM) {
                LivingEntity t = getTarget();
                if (t != null) {
                    lookAtPos(t.getX(), t.getEyeY(), t.getZ());
                }
                int tick = getAttackTick() + 1;
                setAttackTick(tick);
                if (state == STATE_CASTING && SPELL_MAGIC_MISSILE.equals(this.currentCastingSpell)) {
                    Player barrageTarget = this.magicMissileBarrageTarget;
                    if (barrageTarget == null || !barrageTarget.isAlive()) {
                        barrageTarget = t instanceof Player p ? p : null;
                        this.magicMissileBarrageTarget = barrageTarget;
                    }
                    if (barrageTarget != null) {
                        lookAtPos(barrageTarget.getX(), barrageTarget.getEyeY(), barrageTarget.getZ());
                        fireMagicMissileBarrageTick(barrageTarget, tick);
                    }
        if (tick >= 60) {
                        // 魔法飞弹弹幕：每 tick 1 发，20 发/秒，持续 10 秒。
                        setAttackState(STATE_IDLE);
                        setAttackTick(0);
                        this.currentCastingSpell = null;
                        this.magicMissileBarrageTarget = null;
                    }
                } else if (state == STATE_CASTING && SPELL_BLACK_HOLE.equals(this.currentCastingSpell)) {
                    // 黑洞持续期间 Boss 不受自身黑洞吸引：每 tick 停止导航并清空外部拉拽速度。
                    this.getNavigation().stop();
                    this.setDeltaMovement(Vec3.ZERO);
                    this.hurtMarked = true;
                    if (tick >= 100) {
                        // 黑洞只持续 5 秒，到时强制清除 Boss 自己释放的黑洞实体。
                        clearOwnedBlackHoles();
                        setAttackState(STATE_IDLE);
                        setAttackTick(0);
                        this.currentCastingSpell = null;
                        this.magicMissileBarrageTarget = null;
                    }
                } else if (state == STATE_CASTING && tick >= 4) {
                    // 施法完成：动画速度 5 倍，实际施法占用同步缩短到原来的 20%
                    setAttackState(STATE_IDLE);
                    setAttackTick(0);
                    this.currentCastingSpell = null;
                    this.magicMissileBarrageTarget = null;
                }
                if (state == STATE_CHARM) {
                    tickCharm(tick);
                }
                if (state == STATE_THROW_BLADE && tick >= 4) {
                    setAttackState(STATE_IDLE);
                    setAttackTick(0);
                }
            }
            tickUltimate();
        }
    }

    @Override
    public boolean removeWhenFarAway(double distance) { return false; }

    // ======================== 索敌范围禁飞 ========================

    private void suppressFlightInFollowRange() {
        double range = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        for (Player player : this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(range),
                p -> p.isAlive() && !p.isSpectator())) {
            boolean wasFlying = player.getAbilities().flying;
            if (wasFlying) {
                player.getAbilities().flying = false;
                if (player instanceof ServerPlayer serverPlayer) {
                    serverPlayer.onUpdateAbilities();
                }
            }

        }
    }

    private void pullPlayerToGround(Player player) {
        net.minecraft.core.BlockPos start = player.blockPosition();
        for (int y = start.getY(); y >= this.level().getMinBuildHeight(); y--) {
            net.minecraft.core.BlockPos ground = new net.minecraft.core.BlockPos(start.getX(), y - 1, start.getZ());
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(start.getX(), y, start.getZ());
            net.minecraft.core.BlockPos head = feet.above();
            if (this.level().getBlockState(ground).isSolid()
                    && this.level().isEmptyBlock(feet)
                    && this.level().isEmptyBlock(head)) {
                player.teleportTo(feet.getX() + 0.5D, feet.getY() + 0.01D, feet.getZ() + 0.5D);
                player.setDeltaMovement(Vec3.ZERO);
                player.fallDistance = 0.0F;
                player.hurtMarked = true;
                return;
            }
        }
        player.setDeltaMovement(0.0D, -2.0D, 0.0D);
        player.hurtMarked = true;
    }

    // ======================== 受伤瞬移 ========================

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        boolean playerDamage = source.getEntity() instanceof Player;
        boolean playerProjectile = source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile
                && playerDamage;
        if (getSocialPhase() == PHASE_NEUTRAL) {
            if (playerDamage) {
                enterHostileInternal();
            } else {
                hitWithoutDamage = true;
                neutralHurtTicks = 0;
                return false;
            }
        }
        if (getSocialPhase() == PHASE_SLEEPING && sleepInvulnerable) {
            if (!playerDamage) return false;
            sleepInvulnerable = false;
        }
        if (getAttackState() == STATE_CHARM && !playerDamage) {
            return false;
        }
        if (source.getDirectEntity() instanceof net.minecraft.world.entity.projectile.Projectile
                && !playerProjectile) {
            return false;
        }
        if (!playerDamage) {
            return false;
        }
        if (!this.level().isClientSide && this.isAlive()
                && this.teleportCooldown <= 0
                && this.random.nextFloat() < 0.30F) {
            Player nearestPlayer = findNearestPlayerInFollowRange();
            if (nearestPlayer != null) {
                teleportRandomAroundPlayer(nearestPlayer);
            } else {
                teleportRandomNearby();
            }
        }
        float cappedAmount = Math.min(amount, this.getMaxHealth() * 0.02F);
        float resistedAmount = cappedAmount * 0.20F; // 全抗提升 80%
        return super.hurt(source, resistedAmount);
    }

    @Nullable
    public Player findNearestPlayerInFollowRange() {
        double followRange = this.getAttributeValue(Attributes.FOLLOW_RANGE);
        double rangeSqr = followRange * followRange;
        Player nearest = null;
        double nearestDist = Double.MAX_VALUE;
        for (Player player : this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(followRange),
                p -> p.isAlive() && !p.isSpectator())) {
            double dist = this.distanceToSqr(player);
            if (dist <= rangeSqr && dist < nearestDist) {
                nearestDist = dist;
                nearest = player;
            }
        }
        return nearest;
    }

    public boolean teleportNearTarget(LivingEntity target, double preferredDistance) {
        if (target == null || !target.isAlive() || target.level() != this.level() || this.teleportCooldown > 0) return false;
        net.minecraft.util.RandomSource rng = this.random;
        for (int attempt = 0; attempt < 24; attempt++) {
            double angle = rng.nextDouble() * Math.PI * 2.0D;
            double distance = Math.max(3.0D, preferredDistance + rng.nextDouble() * 4.0D - 2.0D);
            double tx = target.getX() + Math.cos(angle) * distance;
            double tz = target.getZ() + Math.sin(angle) * distance;
            for (int dy = 8; dy >= -8; dy--) {
                double ty = target.getY() + dy;
                if (ty < this.level().getMinBuildHeight() || ty > this.level().getMaxBuildHeight() - 3) continue;
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(tx, ty, tz);
                if (this.level().getBlockState(bp).isSolid()
                        && this.level().isEmptyBlock(bp.above())
                        && this.level().isEmptyBlock(bp.above(2))) {
                    this.getNavigation().stop();
                    this.teleportTo(tx, bp.above().getY() + 0.01, tz);
                    this.teleportCooldown = 20;
                    lookAtPos(target.getX(), target.getEyeY(), target.getZ());
                    return true;
                }
            }
        }
        return false;
    }

    /** 瞬移到指定玩家为中心 16 格半径内的随机地面，目的地保证上方 ≥2 格空气无窒息。 */
    private void teleportRandomAroundPlayer(Player center) {
        if (center == null || center.level() != this.level()) return;
        net.minecraft.util.RandomSource rng = this.random;
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = rng.nextInt(32) - 16;
            int dz = rng.nextInt(32) - 16;
            double tx = center.getX() + dx;
            double tz = center.getZ() + dz;
            for (int dy = 16; dy >= -16; dy--) {
                double ty = center.getY() + dy;
                if (ty < this.level().getMinBuildHeight() || ty > this.level().getMaxBuildHeight() - 3) continue;
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(tx, ty, tz);
                if (this.level().getBlockState(bp).isSolid()
                        && this.level().isEmptyBlock(bp.above())
                        && this.level().isEmptyBlock(bp.above(2))) {
                    this.getNavigation().stop();
                    this.teleportTo(tx, bp.above().getY() + 0.01, tz);
                    this.teleportCooldown = 20;
                    this.invulnerableTime = 20;
                    lookAtPos(center.getX(), center.getEyeY(), center.getZ());
                    return;
                }
            }
        }
    }

    /** 兜底：瞬移到自身 16 格半径内的随机地面，目的地保证上方 ≥2 格空气无窒息。 */
    private void teleportRandomNearby() {
        net.minecraft.util.RandomSource rng = this.random;
        for (int attempt = 0; attempt < 32; attempt++) {
            int dx = rng.nextInt(32) - 16;
            int dz = rng.nextInt(32) - 16;
            double tx = this.getX() + dx;
            double tz = this.getZ() + dz;
            // 从当前 Y 向上/下搜索地面
            for (int dy = 16; dy >= -16; dy--) {
                double ty = this.getY() + dy;
                if (ty < this.level().getMinBuildHeight() || ty > this.level().getMaxBuildHeight() - 3) continue;
                net.minecraft.core.BlockPos bp = net.minecraft.core.BlockPos.containing(tx, ty, tz);
                // 脚下方块为固体，且 head 位置两格为空气
                if (this.level().getBlockState(bp).isSolid()
                        && this.level().isEmptyBlock(bp.above())
                        && this.level().isEmptyBlock(bp.above(2))) {
                    this.teleportTo(tx, bp.above().getY() + 0.01, tz);
                    // 瞬移后进入 1 秒冷却，防止连续受击时频繁传送
                    this.teleportCooldown = 20;
                    // 1 秒无敌
                    this.invulnerableTime = 20;
                    return;
                }
            }
        }
    }

    // ========================================================================
    // 锁定方法（所有技能释放前由 FinaleCombatGoal 调用，防 miss）
    // ========================================================================

    /**
     * 技能释放前只强制 Boss 面朝目标玩家，不再冻结或回拉玩家。<br>
     * 旧逻辑会在索敌范围内反复清除玩家速度并同步位置，导致玩家无法移动或移动困难。
     */
    public void lockOntoPlayer(Player target) {
        if (target == null || !target.isAlive() || target.level() != this.level()) return;
        lookAtPos(target.getX(), target.getEyeY(), target.getZ());
    }

    /** 仅面朝某个坐标点（不冻结玩家）。 */
    public void lookAtPos(double x, double y, double z) {
        double dx = x - this.getX();
        double dz = z - this.getZ();
        double dy = y - this.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Math.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
        float pitch = (float)(-(Math.atan2(dy, horizontal) * (180.0 / Math.PI)));
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
    }

    // ========================================================================
    // 技能挑选
    // ========================================================================

    public String pickIronSpell() {
        String[] pool = { SPELL_BLACK_HOLE, SPELL_MAGIC_MISSILE, SPELL_STARFALL,
                          SPELL_CHAOS_FLAME, SPELL_DEATH_SMOKE, SPELL_ANNIHILATION_RAY };
        return pool[this.random.nextInt(pool.length)];
    }

    public String pickGoetyCrystal() {
        String[] pool = { CRYSTAL_REND, CRYSTAL_CULT, CRYSTAL_CORRUPT,
                          CRYSTAL_WITHER, CRYSTAL_BIND, CRYSTAL_SHOCK };
        return pool[this.random.nextInt(pool.length)];
    }

    // ========================================================================
    // 魅惑终结技
    // ========================================================================

    public void beginCharm(Player target) {
        if (!canBeginCharmOrUltimate()) return;
        if (target == null || !target.isAlive() || target.level() != this.level()) return;
        if (this.getHealth() >= this.getMaxHealth() * 0.50F) {
            target.sendSystemMessage(net.minecraft.network.chat.Component.literal("§7落幕之终焉的魅惑终结技尚未解放。"));
            return;
        }
        startCharmUltimateCooldown();
        snapBossToGround();
        this.charmedTarget = target;
        randomizeCastAnimation();
        setAttackState(STATE_CHARM);
        setAttackTick(0);
        this.setInvulnerable(false);
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        teleportFacingTarget(target);
        spawnCastBurst(target, 3);
        target.sendSystemMessage(net.minecraft.network.chat.Component.literal("§5落幕之终焉正在魅惑你，3秒后将释放终结技！"));
    }

    private void tickCharm(int tick) {
        Player target = this.charmedTarget;
        this.setInvulnerable(false);
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.hurtMarked = true;
        if (target == null || !target.isAlive()) {
            setAttackState(STATE_IDLE);
            setAttackTick(0);
            this.charmedTarget = null;
            this.setInvulnerable(false);
            return;
        }
        teleportFacingTarget(target);
        if (tick >= 60) {
            this.charmedTarget = null;
            setAttackState(STATE_IDLE);
            setAttackTick(0);
            startUltimateSequence(target);
        }
    }

    private void snapBossToGround() {
        net.minecraft.core.BlockPos start = this.blockPosition();
        for (int y = start.getY(); y >= this.level().getMinBuildHeight(); y--) {
            net.minecraft.core.BlockPos ground = new net.minecraft.core.BlockPos(start.getX(), y - 1, start.getZ());
            net.minecraft.core.BlockPos feet = new net.minecraft.core.BlockPos(start.getX(), y, start.getZ());
            net.minecraft.core.BlockPos head = feet.above();
            if (this.level().getBlockState(ground).isSolid()
                    && this.level().isEmptyBlock(feet)
                    && this.level().isEmptyBlock(head)) {
                this.getNavigation().stop();
                this.setDeltaMovement(Vec3.ZERO);
                this.teleportTo(feet.getX() + 0.5D, feet.getY() + 0.01D, feet.getZ() + 0.5D);
                this.fallDistance = 0.0F;
                this.hurtMarked = true;
                return;
            }
        }
    }

    private void teleportFacingTarget(Player target) {
        double dx = target.getX() - this.getX();
        double dz = target.getZ() - this.getZ();
        double dy = target.getEyeY() - this.getEyeY();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float)(Math.atan2(dz, dx) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float)(-(Math.atan2(dy, horizontal) * (180.0D / Math.PI)));
        this.getNavigation().stop();
        this.setDeltaMovement(Vec3.ZERO);
        this.absMoveTo(this.getX(), this.getY(), this.getZ(), yaw, pitch);
        this.setYRot(yaw);
        this.setXRot(pitch);
        this.yBodyRot = yaw;
        this.yHeadRot = yaw;
        this.hurtMarked = true;
    }

    private void forcePlayerLookAtBoss(Player target) {
        Vec3 delta = this.getEyePosition().subtract(target.getEyePosition());
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float)(Math.atan2(delta.z, delta.x) * (180.0D / Math.PI)) - 90.0F;
        float pitch = (float)(-(Math.atan2(delta.y, horizontal) * (180.0D / Math.PI)));
        target.setYRot(yaw);
        target.setXRot(pitch);
        target.yHeadRot = yaw;
        target.yBodyRot = yaw;
        if (target instanceof ServerPlayer serverPlayer) {
            serverPlayer.connection.teleport(serverPlayer.getX(), serverPlayer.getY(), serverPlayer.getZ(), yaw, pitch);
        }
    }

    // ========================================================================
    // 低血量阶段：星辰之矢
    // ========================================================================

    private void tickLowHealthStarArrows() {
        if (this.getHealth() >= this.getMaxHealth() * 0.15F) return;
        if (!(this.level() instanceof ServerLevel server)) return;

        if (this.pendingStarStrikePos != null) {
            this.getNavigation().stop();
            this.setDeltaMovement(Vec3.ZERO);
            this.hurtMarked = true;
            renderStarStrikeRing(server, this.pendingStarStrikePos, 20.0D);
            if (this.starStrikeWindup > 0 && --this.starStrikeWindup == 0) {
                fireDescendingStarArrows(this.pendingStarStrikePos);
                this.pendingStarStrikePos = null;
            }
            return;
        }

        if (this.lowHealthStarArrowCooldown > 0) {
            this.lowHealthStarArrowCooldown--;
            return;
        }
        Player target = findNearestPlayerInFollowRange();
        if (target == null || this.distanceToSqr(target) > 20.0D * 20.0D) return;
        this.lowHealthStarArrowCooldown = 120;
        this.pendingStarStrikePos = this.position();
        this.starStrikeWindup = 30;
        this.starStrikeImpactDelay = 0;
        com.endsustain.EndSustain.LOGGER.info("[终焉维系] 尾杀领域已展开：中心={}，半径=20，领域内玩家={}",
                this.pendingStarStrikePos, target.getGameProfile().getName());
        server.playSound(null, target.blockPosition(), SoundEvents.BEACON_ACTIVATE,
                this.getSoundSource(), 1.8F, 1.7F);
    }

    private void renderStarStrikeRing(ServerLevel server, Vec3 center, double radius) {
        if (this.tickCount % 2 != 0) return;
        int points = radius >= 20.0D ? 160 : 32;
        for (int i = 0; i < points; i++) {
            double angle = Math.PI * 2.0D * i / points;
            server.sendParticles(i % 3 == 0 ? ParticleTypes.END_ROD : ParticleTypes.REVERSE_PORTAL,
                    center.x + Math.cos(angle) * radius, center.y + 0.12D,
                    center.z + Math.sin(angle) * radius,
                    1, 0.0D, 0.015D, 0.0D, 0.0D);
        }
    }

    private void fireDescendingStarArrows(Vec3 center) {
        if (!(this.level() instanceof ServerLevel server)) return;
        EntityType<?> starArrowType = ForgeRegistries.ENTITY_TYPES.getValue(
                new ResourceLocation("revelationfix", "star_arrow"));
        if (starArrowType == null) {
            com.endsustain.EndSustain.LOGGER.error("[终焉维系] 找不到 revelationfix:star_arrow，尾杀未生成弹体");
            return;
        }
        this.starStrikeDomainCenter = center;
        this.starStrikeDomainTicks = 100;
        int spawned = 0;
        for (int direction = 0; direction < 8; direction++) {
            double angle = Math.PI * 2.0D * direction / 8.0D;
            for (int index = 1; index <= 5; index++) {
                double distance = index * 4.0D;
                Entity starArrow = starArrowType.create(server);
                if (starArrow == null) continue;
                starArrow.setPos(center.x + Math.cos(angle) * distance,
                        center.y + 28.0D,
                        center.z + Math.sin(angle) * distance);
                starArrow.addTag("endsustain_star_arrow");
                starArrow.addTag("endsustain_tail_kill_star_arrow");
                if (starArrow instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                    projectile.setOwner(this);
                    projectile.shoot(0.0D, -1.0D, 0.0D, 2.6F, 0.0F);
                } else {
                    starArrow.setDeltaMovement(0.0D, -2.6D, 0.0D);
                }
                try {
                    starArrow.getClass().getMethod("setPower", float.class).invoke(starArrow, 10.0F);
                    starArrow.getClass().getMethod("setDamageMultiplier", float.class).invoke(starArrow, 1.0F);
                    starArrow.getClass().getMethod("setTrailLifeTime", int.class).invoke(starArrow, 20);
                } catch (ReflectiveOperationException ignored) {}
                starArrow.hurtMarked = true;
                if (server.addFreshEntity(starArrow)) spawned++;
            }
        }
        com.endsustain.EndSustain.LOGGER.info(
                "[终焉维系] 尾杀领域已生成 {} 支 revelationfix:star_arrow，中心={}，半径=20",
                spawned, center);
        server.playSound(null, BlockPos.containing(center), SoundEvents.TRIDENT_THUNDER,
                this.getSoundSource(), 2.0F, 1.25F);
    }

    private void resolveStarStrike(ServerLevel server, Vec3 center) {
        renderStarStrikeRing(server, center, 20.0D);
        server.sendParticles(ParticleTypes.END_ROD, center.x, center.y + 0.4D, center.z,
                240, 19.5D, 1.4D, 19.5D, 0.12D);
        server.sendParticles(ParticleTypes.REVERSE_PORTAL, center.x, center.y + 0.4D, center.z,
                320, 19.5D, 1.0D, 19.5D, 0.18D);
        server.playSound(null, BlockPos.containing(center), SoundEvents.GENERIC_EXPLODE,
                this.getSoundSource(), 2.4F, 0.55F);
    }

    @Nullable
    private net.minecraft.world.entity.Entity createRevelationStarProjectile(ServerLevel server, Vec3 spawn, Vec3 direction, Player target) {
        // 游戏内该实体显示为 revelationfix 所属时，优先从运行时注册表查找该命名空间的候选实体。
        // Goety Revelation 2.3.1 的源码包里没有公开 ModEntityType 字段，因此这里不能只依赖反射类名。
        String[] candidates = {
                "star_arrow",
                "quietus_bolt",
                "quietus_star",
                "star_bolt",
                "star",
                "stellar_bolt",
                "cosmic_bolt",
                "nether_star"
        };
        for (String id : candidates) {
            try {
                EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("revelationfix", id));
                if (type == null) continue;
                net.minecraft.world.entity.Entity entity = type.create(server);
                if (entity != null) {
                    initializeRevelationStarProjectile(entity, spawn, direction, target);
                    com.endsustain.EndSustain.LOGGER.debug("[终焉维系] 使用 revelationfix:{} 生成星辰之矢", id);
                    return entity;
                }
            } catch (Throwable ignored) {
                // 尝试下一个候选实体 ID。
            }
        }

        try {
            Class<?> goetyEntityTypes = Class.forName("com.Polarice3.Goety.common.entities.ModEntityType");
            Object magicBoltReg = goetyEntityTypes.getField("MAGIC_BOLT").get(null);
            Object magicBoltType = Class.forName("net.minecraftforge.registries.RegistryObject")
                    .getMethod("get")
                    .invoke(magicBoltReg);

            Class<?> quietusBoltClass = Class.forName("z1gned.goetyrevelation.entitiy.QuietusBolt");
            Object bolt = quietusBoltClass
                    .getConstructor(net.minecraft.world.entity.EntityType.class, Level.class)
                    .newInstance(magicBoltType, server);
            if (bolt instanceof net.minecraft.world.entity.Entity entity) {
                initializeRevelationStarProjectile(entity, spawn, direction, target);
                return entity;
            }
        } catch (Throwable ignored) {
            // 未加载 Goety Revelation 或构造失败时使用兜底可见弹体。
        }
        return null;
    }

    private void initializeRevelationStarProjectile(net.minecraft.world.entity.Entity entity, Vec3 spawn, Vec3 direction, Player target) {
        entity.setPos(spawn.x, spawn.y, spawn.z);
        entity.addTag("endsustain_star_arrow");
        Vec3 normalizedDirection = direction.normalize();
        if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
            projectile.setOwner(this);
            projectile.shoot(normalizedDirection.x, normalizedDirection.y, normalizedDirection.z, 2.8F, 0.0F);
        } else {
            entity.setDeltaMovement(normalizedDirection.scale(2.8D));
        }
        if (entity instanceof net.minecraft.world.entity.projectile.AbstractArrow arrow) {
            arrow.setBaseDamage(28.0D);
            arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
            arrow.setPierceLevel((byte) 0);
            arrow.setCritArrow(true);
        }
        try {
            entity.getClass().getMethod("setTarget", LivingEntity.class).invoke(entity, target);
        } catch (Throwable ignored) {}
        try {
            entity.getClass().getMethod("setExtraDamage", float.class).invoke(entity, 12.0F);
        } catch (Throwable ignored) {}
        try {
            entity.getClass().getMethod("setDamage", float.class).invoke(entity, 12.0F);
        } catch (Throwable ignored) {}
        try {
            entity.getClass().getMethod("setExtraDuration", int.class).invoke(entity, 80);
        } catch (Throwable ignored) {}
        entity.hurtMarked = true;
    }

    private void tickStarArrowTrueKill() {
        if (!(this.level() instanceof ServerLevel server)) return;
        if (this.starStrikeDomainCenter == null || this.starStrikeDomainTicks-- <= 0) {
            this.starStrikeDomainCenter = null;
            return;
        }
        EntityType<?> starArrowType = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation("revelationfix", "star_arrow"));
        if (starArrowType == null) return;

        java.util.List<net.minecraft.world.entity.Entity> starArrows = server.getEntities(
                this,
                this.getBoundingBox().inflate(96.0D),
                entity -> entity.isAlive()
                        && entity.getType() == starArrowType
                        && entity.getTags().contains("endsustain_star_arrow")
        );
        if (starArrows.isEmpty()) return;

        for (Player player : server.players()) {
            if (!player.isAlive() || player.level() != this.level()) continue;
            double domainX = player.getX() - this.starStrikeDomainCenter.x;
            double domainZ = player.getZ() - this.starStrikeDomainCenter.z;
            if (domainX * domainX + domainZ * domainZ > 20.0D * 20.0D) continue;
            for (net.minecraft.world.entity.Entity starArrow : starArrows) {
                double auraX = player.getX() - starArrow.getX();
                double auraZ = player.getZ() - starArrow.getZ();
                if (auraX * auraX + auraZ * auraZ <= 6.0D * 6.0D) {
                    com.endsustain.EndSustain.LOGGER.info(
                            "[终焉维系] 星辰矢 6 格强杀领域命中玩家：{}，箭体坐标={}",
                            player.getGameProfile().getName(), starArrow.position());
                    player.setInvulnerable(false);
                    player.invulnerableTime = 0;
                    player.hurt(this.damageSources().fellOutOfWorld(), (float) Integer.MAX_VALUE);
                    if (player.isAlive()) {
                        forceTrueKill(player);
                    }
                    starArrow.discard();
                    break;
                }
            }
        }
    }

    public void fireStarArrowsAt(Player target) {
        if (!(this.level() instanceof ServerLevel server) || target == null || !target.isAlive() || target.level() != this.level()) return;
        for (int i = 0; i < 10; i++) {
            double ox = (this.random.nextDouble() - 0.5D) * 1.5D;
            double oz = (this.random.nextDouble() - 0.5D) * 1.5D;
            Vec3 spawn = new Vec3(this.getX() + ox, this.getEyeY() + 0.25D + i * 0.03D, this.getZ() + oz);
            Vec3 aim = target.getEyePosition().add(
                    (this.random.nextDouble() - 0.5D) * 0.8D,
                    (this.random.nextDouble() - 0.5D) * 0.5D,
                    (this.random.nextDouble() - 0.5D) * 0.8D)
                    .subtract(spawn)
                    .normalize();
            net.minecraft.world.entity.Entity star = createRevelationStarProjectile(server, spawn, aim, target);
            if (star != null) {
                server.addFreshEntity(star);
            } else {
                net.minecraft.world.entity.projectile.SpectralArrow arrow = net.minecraft.world.entity.EntityType.SPECTRAL_ARROW.create(server);
                if (arrow == null) continue;
                arrow.setOwner(this);
                arrow.setPos(spawn.x, spawn.y, spawn.z);
                arrow.shoot(aim.x, aim.y, aim.z, 3.0F, 0.2F);
                arrow.setBaseDamage(28.0D);
                arrow.pickup = net.minecraft.world.entity.projectile.AbstractArrow.Pickup.DISALLOWED;
                server.addFreshEntity(arrow);
            }
        }
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                this.getX(), this.getEyeY(), this.getZ(), 40, 0.7D, 0.7D, 0.7D, 0.12D);
        server.playSound(null, this.getX(), this.getY(), this.getZ(),
                net.minecraft.sounds.SoundEvents.AMETHYST_CLUSTER_BREAK,
                this.getSoundSource(), 2.0F, 1.6F);
    }

    // ========================================================================
    // 无条件被动：末影水晶 / 重生锚爆破
    // ========================================================================

    private void tickPassiveCrystalAnchorAttack() {
        if (this.passiveCrystalAnchorCooldown > 0) {
            this.passiveCrystalAnchorCooldown--;
            return;
        }
        if (!(this.level() instanceof ServerLevel server)) return;
        LivingEntity target = findPassiveExplosionTarget();
        if (target == null) return;
        this.passiveCrystalAnchorCooldown = 40;
        spawnAndDetonateEndCrystal(server, target);
        placeAndDetonateRespawnAnchor(server, target);
    }

    @Nullable
    private LivingEntity findPassiveExplosionTarget() {
        var box = this.getBoundingBox().inflate(16.0D);
        java.util.List<LivingEntity> living = this.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e.isAlive() && e != this && !(e instanceof Player) && !e.isSpectator());
        if (living.isEmpty()) return null;
        living.sort(java.util.Comparator.comparingDouble(this::distanceToSqr));
        return living.get(0);
    }

    private void spawnAndDetonateEndCrystal(ServerLevel server, LivingEntity target) {
        double angle = this.random.nextDouble() * Math.PI * 2.0D;
        double radius = 1.5D + this.random.nextDouble() * 2.5D;
        double x = target.getX() + Math.cos(angle) * radius;
        double z = target.getZ() + Math.sin(angle) * radius;
        double y = target.getY() + 1.0D;
        net.minecraft.world.entity.boss.enderdragon.EndCrystal crystal =
                net.minecraft.world.entity.EntityType.END_CRYSTAL.create(server);
        if (crystal != null) {
            crystal.setPos(x, y, z);
            server.addFreshEntity(crystal);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                    x, y, z, 24, 0.3D, 0.3D, 0.3D, 0.08D);
            crystal.discard();
        }
        for (int i = 0; i < 24; i++) {
            double ringAngle = Math.PI * 2.0D * i / 24.0D + this.tickCount * 0.08D;
            double ringRadius = 2.4D;
            double px = x + Math.cos(ringAngle) * ringRadius;
            double pz = z + Math.sin(ringAngle) * ringRadius;
            server.sendParticles(i % 2 == 0 ? ParticleTypes.REVERSE_PORTAL : ParticleTypes.END_ROD,
                    px, y + 0.25D + Math.sin(ringAngle * 2.0D) * 0.15D, pz,
                    1, 0.0D, 0.02D, 0.0D, 0.0D);
        }
        server.explode(this, x, y, z, 4.0F, Level.ExplosionInteraction.MOB);
    }

    private void placeAndDetonateRespawnAnchor(ServerLevel server, LivingEntity target) {
        net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(
                target.getX() + this.random.nextInt(5) - 2,
                target.getY(),
                target.getZ() + this.random.nextInt(5) - 2);
        for (int i = 0; i < 4 && !server.isEmptyBlock(pos); i++) {
            pos = pos.above();
        }
        if (server.isEmptyBlock(pos)) {
            server.setBlock(pos, net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE, 4), 3);
            server.sendParticles(net.minecraft.core.particles.ParticleTypes.PORTAL,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    32, 0.4D, 0.4D, 0.4D, 0.12D);
            server.explode(this, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
                    5.0F, Level.ExplosionInteraction.MOB);
            server.removeBlock(pos, false);
        } else {
            server.explode(this, target.getX(), target.getY() + 0.5D, target.getZ(),
                    5.0F, Level.ExplosionInteraction.MOB);
        }
    }

    // ========================================================================
    // 技能施放入口
    // ========================================================================

    private void spawnCastBurst(Player target, int style) {
        if (!(this.level() instanceof ServerLevel server)) return;
        double y = this.getY() + this.getBbHeight() * 0.55D;
        var primary = style == 1 ? net.minecraft.core.particles.ParticleTypes.SOUL_FIRE_FLAME
                : style == 2 ? net.minecraft.core.particles.ParticleTypes.CRIT
                : style == 3 ? net.minecraft.core.particles.ParticleTypes.HEART
                : net.minecraft.core.particles.ParticleTypes.WITCH;
        server.sendParticles(primary, this.getX(), y, this.getZ(),
                style == 4 ? 90 : 36, 1.0D, 0.75D, 1.0D, 0.08D);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                this.getX(), y, this.getZ(), style == 4 ? 140 : 52,
                style == 4 ? 2.8D : 1.25D, 0.9D, style == 4 ? 2.8D : 1.25D, 0.18D);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                this.getX(), y + 0.35D, this.getZ(), style == 4 ? 70 : 24,
                0.8D, 1.0D, 0.8D, 0.06D);
        if (target != null) {
            Vec3 from = this.getEyePosition();
            Vec3 delta = target.getEyePosition().subtract(from);
            for (int i = 1; i <= 10; i++) {
                Vec3 point = from.add(delta.scale(i / 11.0D));
                server.sendParticles(net.minecraft.core.particles.ParticleTypes.ENCHANT,
                        point.x, point.y, point.z, 2, 0.08D, 0.08D, 0.08D, 0.0D);
            }
        }
    }

    private void spawnCastingAuraParticles(int state) {
        if (!(this.level() instanceof ServerLevel server)) return;
        double angle = this.tickCount * 0.42D;
        double radius = state >= STATE_ULT_RAISE && state <= STATE_ULT_SHEATHE ? 2.2D : 1.15D;
        for (int i = 0; i < 6; i++) {
            double a = angle + Math.PI * 2.0D * i / 6.0D;
            double x = this.getX() + Math.cos(a) * radius;
            double z = this.getZ() + Math.sin(a) * radius;
            double y = this.getY() + 0.25D + (i % 3) * 0.55D;
            server.sendParticles(i % 2 == 0 ? net.minecraft.core.particles.ParticleTypes.WITCH
                            : net.minecraft.core.particles.ParticleTypes.REVERSE_PORTAL,
                    x, y, z, 1, 0.03D, 0.05D, 0.03D, 0.01D);
        }
    }

    /** 施放铁魔法法术 —— 优先调用 Iron's Spellbooks 原版 API。 */
    public void castIronSpell(Player target, String spellId) {
        this.currentCastingSpell = spellId;
        this.magicMissileBarrageTarget = SPELL_MAGIC_MISSILE.equals(spellId) ? target : null;
        randomizeCastAnimation();
        setAttackState(STATE_CASTING);
        setAttackTick(0);
        lookAtPos(target.getX(), target.getEyeY(), target.getZ());
        spawnCastBurst(target, 0);

        if (SPELL_MAGIC_MISSILE.equals(spellId)) {
            // 魔法飞弹为连续弹幕：后续由 aiStep 每 tick 发射，持续 10 秒。
            fireMagicMissileBarrageTick(target, 0);
            return;
        }

        if (SPELL_STARFALL.equals(spellId)) {
            // Iron's Spellbooks 的星海落瀑在部分环境中只显示目标区域，不生成实际降落伤害实体。
            // 因此无论原 API 是否成功，都额外生成一批原生下落法术实体作为可靠伤害补偿。
            com.endsustain.compat.irons.IronsSpellbooksCompat.castSpell(this, target, spellId);
            spawnStarfallMeteors(target);
            return;
        }

        // 尝试 Iron's Spellbooks 真实法术
        if (com.endsustain.compat.irons.IronsSpellbooksCompat.castSpell(this, target, spellId)) {
            return;
        }
        // 降级：原生弹射物
        if (this.level() instanceof ServerLevel server) {
            spawnSpellProjectile(target, spellDamage(spellId));
            server.playSound(null, this.getX(), this.getY(), this.getZ(),
                    net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL,
                    this.getSoundSource(), 2.0F, 0.8F + this.random.nextFloat() * 0.4F);
        }
    }

    /** 施放诡厄巫法聚晶 —— 优先调用 Goety 原版 API。 */
    public void castGoetyCrystal(Player target, String crystalId) {
        this.currentCastingSpell = crystalId;
        randomizeCastAnimation();
        setAttackState(STATE_CASTING);
        setAttackTick(0);
        lookAtPos(target.getX(), target.getEyeY(), target.getZ());
        spawnCastBurst(target, 1);

        // 尝试 Goety 真实聚晶
        if (com.endsustain.compat.goety.GoetyCompat.castCrystal(this, target, crystalId)) {
            return;
        }
        // 降级：原生弹射物
        if (this.level() instanceof ServerLevel server) {
            spawnSpellProjectile(target, 10.0F);
            server.playSound(null, this.getX(), this.getY(), this.getZ(),
                    net.minecraft.sounds.SoundEvents.WARDEN_SONIC_BOOM,
                    this.getSoundSource(), 1.5F, 0.6F + this.random.nextFloat() * 0.4F);
        }
    }

    private float spellDamage(String spellId) {
        return switch (spellId) {
            case SPELL_BLACK_HOLE -> 12.0F;
            case SPELL_MAGIC_MISSILE -> 6.0F;
            case SPELL_STARFALL -> 18.0F;
            case SPELL_CHAOS_FLAME -> 14.0F;
            case SPELL_DEATH_SMOKE -> 10.0F;
            case SPELL_ANNIHILATION_RAY -> 35.0F;
            default -> 8.0F;
        };
    }

    // =========== 降级弹射物 ===========

    private void clearOwnedBlackHoles() {
        if (!(this.level() instanceof ServerLevel server)) return;
        var box = this.getBoundingBox().inflate(96.0D);
        for (net.minecraft.world.entity.Entity entity : server.getEntities(this, box,
                e -> "io.redspace.ironsspellbooks.entity.spells.black_hole.BlackHole".equals(e.getClass().getName()))) {
            if (entity instanceof net.minecraft.world.entity.projectile.Projectile projectile) {
                net.minecraft.world.entity.Entity owner = projectile.getOwner();
                if (owner != null && owner != this) continue;
            }
            entity.discard();
        }
    }

    private void fireMagicMissileBarrageTick(Player target, int tick) {
        if (!(this.level() instanceof ServerLevel server)) return;
        if (!target.isAlive()) return;
        target.invulnerableTime = 0;
        // 魔法飞弹要求 20 发/秒；现在每次技能按 3 重释放处理，因此每 tick 同时发射 3 发。
        for (int i = 0; i < 3; i++) {
            if (!com.endsustain.compat.irons.IronsSpellbooksCompat.castSpell(this, target, SPELL_MAGIC_MISSILE)) {
                spawnSpellProjectile(target, spellDamage(SPELL_MAGIC_MISSILE));
            }
        }
        if (tick % 10 == 0) {
            server.playSound(null, this.getX(), this.getY(), this.getZ(),
                    net.minecraft.sounds.SoundEvents.EVOKER_CAST_SPELL,
                    this.getSoundSource(), 1.2F, 1.35F + this.random.nextFloat() * 0.25F);
        }
    }

    private void spawnStarfallMeteors(Player target) {
        if (!(this.level() instanceof ServerLevel server)) return;
        float damage = spellDamage(SPELL_STARFALL);
        for (int i = 0; i < 12; i++) {
            double angle = this.random.nextDouble() * Math.PI * 2.0D;
            double radius = this.random.nextDouble() * 5.5D;
            double x = target.getX() + Math.cos(angle) * radius;
            double z = target.getZ() + Math.sin(angle) * radius;
            double y = target.getY() + 18.0D + this.random.nextDouble() * 8.0D;
            SpellProjectile meteor = new SpellProjectile(this.level(), this, null, damage, 3.0F);
            meteor.setPos(x, y, z);
            Vec3 fall = new Vec3(target.getX() - x, target.getY() + 0.5D - y, target.getZ() - z)
                    .normalize()
                    .scale(1.6D + this.random.nextDouble() * 0.45D)
                    .add(0.0D, -0.75D, 0.0D);
            meteor.setDeltaMovement(fall);
            this.level().addFreshEntity(meteor);
        }
        server.playSound(null, target.getX(), target.getY(), target.getZ(),
                net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE,
                this.getSoundSource(), 1.8F, 0.65F + this.random.nextFloat() * 0.2F);
        server.sendParticles(net.minecraft.core.particles.ParticleTypes.END_ROD,
                target.getX(), target.getY() + 1.0D, target.getZ(),
                60, 5.5D, 0.2D, 5.5D, 0.05D);
    }

    private void spawnSpellProjectile(Player target, float damage) {
        if (!(this.level() instanceof ServerLevel)) return;
        SpellProjectile proj = new SpellProjectile(this.level(), this, target, damage);
        proj.setPos(this.getX(), this.getEyeY() - 0.3, this.getZ());
        proj.setDeltaMovement(target.getEyePosition().subtract(proj.position()).normalize().scale(0.8));
        this.level().addFreshEntity(proj);
    }

    /** 投掷制导终焉之刃。 */
    public void throwEndsustainBlade(Player target) {
        setAttackState(STATE_THROW_BLADE);
        setAttackTick(0);
        if (!(this.level() instanceof ServerLevel)) return;
        lookAtPos(target.getX(), target.getEyeY(), target.getZ());
        spawnCastBurst(target, 2);
        EndsustainBladeEntity blade = new EndsustainBladeEntity(this.level(), this, target);
        blade.shootFromRotation(this, this.getXRot(), this.getYRot(), 0.0F, 2.8F, 0.0F);
        this.level().addFreshEntity(blade);
    }

    // ========================================================================
    // 必杀技
    // ========================================================================

    public boolean canBeginCharmOrUltimate() {
        return this.ultimateCooldown <= 0;
    }

    private void startCharmUltimateCooldown() {
        this.ultimateCooldown = ULTIMATE_COOLDOWN_TICKS;
    }

    public void beginUltimate(Player target) {
        if (!canBeginCharmOrUltimate()) return;
        if (target == null || !target.isAlive() || target.level() != this.level()) return;
        startCharmUltimateCooldown();
        startUltimateSequence(target);
    }

    private void startUltimateSequence(Player target) {
        this.ultTarget = target;
        this.ultLockedPos = target.position();
        this.ultEntryPos = this.position();
        Vec3 across = this.ultLockedPos.subtract(this.ultEntryPos);
        if (across.lengthSqr() < 1.0E-4D) across = Vec3.directionFromRotation(0.0F, this.getYRot());
        this.ultExitPos = this.ultLockedPos.add(across.normalize().scale(Math.max(5.0D, across.length())));
        this.ultDamagePending = false;
        setAttackState(STATE_ULT_RAISE);
        setAttackTick(0);
        this.bossEvent.setColor(BossEvent.BossBarColor.RED);
        this.setInvulnerable(true);
        if (this.level() instanceof ServerLevel server) {
            com.endsustain.combat.TimeStopManager.begin(server, this, 80);
        }
        spawnCastBurst(target, 4);
    }

    private void tickUltimate() {
        int state = getAttackState();
        int tick  = getAttackTick();
        switch (state) {
            case STATE_ULT_RAISE   -> tickUltRaise(tick);
            case STATE_ULT_DASH    -> tickUltDash(tick);
            case STATE_ULT_SHEATHE -> tickUltSheathe(tick);
            default -> {
                if (this.ultTarget != null || this.ultDamagePending) {
                    if (this.level() instanceof ServerLevel server) {
                        com.endsustain.combat.TimeStopManager.end(server);
                    }
                    this.ultDamagePending = false;
                    this.ultTarget = null;
                    this.ultLockedPos = null;
                    this.ultEntryPos = null;
                    this.ultExitPos = null;
                    this.setInvulnerable(false);
                    this.bossEvent.setColor(BossEvent.BossBarColor.PURPLE);
                }
            }
        }
    }

    private void tickUltRaise(int tick) {
        if (this.ultLockedPos == null || this.ultEntryPos == null || this.ultExitPos == null) {
            setAttackState(STATE_IDLE);
            setAttackTick(0);
            this.ultTarget = null;
            this.setInvulnerable(false);
            this.bossEvent.setColor(BossEvent.BossBarColor.PURPLE);
            return;
        }
        lookAtLockedPos();
        if (tick >= 30) {
            setAttackState(STATE_ULT_DASH);
            setAttackTick(0);
        } else { setAttackTick(tick + 1); }
    }

    private void tickUltDash(int tick) {
        if (this.ultEntryPos == null || this.ultLockedPos == null || this.ultExitPos == null) {
            setAttackState(STATE_IDLE);
            setAttackTick(0);
            return;
        }
        lookAtLockedPos();
        float progress = Math.min(1.0F, tick / 12.0F);
        Vec3 path = this.ultExitPos.subtract(this.ultEntryPos);
        Vec3 next = this.ultEntryPos.add(path.scale(progress));
        this.teleportTo(next.x, next.y, next.z);
        this.setDeltaMovement(Vec3.ZERO);
        this.hurtMarked = true;
        if (!this.ultDamagePending && progress >= 0.5F) {
            this.ultDamagePending = true;
            applyTrueDamage();
            if (this.level() instanceof ServerLevel server) {
                com.endsustain.network.EndSustainNetwork.sendScreenTear(
                        server, this.ultLockedPos, 48.0D, 16);
                server.sendParticles(ParticleTypes.REVERSE_PORTAL,
                        this.ultLockedPos.x, this.ultLockedPos.y + 1.0D, this.ultLockedPos.z,
                        140, 1.8D, 1.2D, 1.8D, 0.22D);
            }
        }
        if (progress >= 1.0F) {
            setAttackState(STATE_ULT_SHEATHE);
            setAttackTick(0);
        } else {
            setAttackTick(tick + 1);
        }
    }

    private void tickUltSheathe(int tick) {
        this.setDeltaMovement(Vec3.ZERO);
        if (tick >= 8) {
            this.ultDamagePending = true;
            setAttackState(STATE_IDLE);
            setAttackTick(0);
        } else { setAttackTick(tick + 1); }
    }

    private void lockTargetPosition() {
        if (ultTarget == null || !ultTarget.isAlive()) return;
        this.ultLockedPos = ultTarget.position();
        ultTarget.setDeltaMovement(Vec3.ZERO);
        ultTarget.fallDistance = 0;
        ultTarget.hurtMarked = true;
        ultTarget.teleportTo(ultLockedPos.x, ultLockedPos.y, ultLockedPos.z);
    }

    private void lookAtLockedPos() {
        if (ultLockedPos == null) return;
        lookAtPos(ultLockedPos.x, ultLockedPos.y, ultLockedPos.z);
    }

    private void applyTrueDamage() {
        if (!(this.level() instanceof ServerLevel server)) return;
        java.util.List<Player> targets = new java.util.ArrayList<>();
        for (Player p : server.players()) {
            boolean onPath = ultLockedPos != null && p.position().distanceTo(ultLockedPos) <= 6.0D;
            boolean isTarget = ultTarget != null && p == ultTarget;
            if (onPath || isTarget) targets.add(p);
        }
        for (Player p : targets) {
            forceTrueKill(p);
        }
    }

    private void forceTrueKill(Player target) {
        TrueKillUtil.forceKill(target, this.damageSources().fellOutOfWorld(), this,
                (float) Integer.MAX_VALUE, false);
    }
}
