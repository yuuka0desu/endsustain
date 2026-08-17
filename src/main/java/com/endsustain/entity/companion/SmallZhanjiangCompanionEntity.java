package com.endsustain.entity.companion;

import com.endsustain.entity.ModEntities;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

public class SmallZhanjiangCompanionEntity extends PathfinderMob implements GeoEntity {
    private static final EntityDimensions MARKER_DIMENSIONS = EntityDimensions.fixed(0.0F, 0.0F);
    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    private UUID ownerUuid;

    public SmallZhanjiangCompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setInvulnerable(true); setNoGravity(true); noPhysics = true; noCulling = true;
    }

    public static AttributeSupplier.Builder createAttributes() { return Mob.createMobAttributes(); }

    @Override public EntityDimensions getDimensions(Pose pose) { return MARKER_DIMENSIONS; }
    @Override public boolean canCollideWith(Entity entity) { return false; }

    @Override protected void defineSynchedData() { super.defineSynchedData(); }
    public UUID getOwnerUuid() { return ownerUuid; }
    public void setOwner(Player owner) { ownerUuid = owner.getUUID(); }

    @Override public void tick() {
        super.tick(); clearFire(); setAirSupply(getMaxAirSupply()); setDeltaMovement(Vec3.ZERO); fallDistance = 0.0F;
        if (level().isClientSide || ownerUuid == null || !(level() instanceof ServerLevel server)) return;
        Entity found = server.getEntity(ownerUuid);
        if (!(found instanceof Player owner) || !owner.isAlive()) { discard(); return; }
        positionAtOwner(owner);
    }

    public void positionAtOwner(Player owner) {
        double yaw = Math.toRadians(owner.getYRot());
        double leftX = Math.cos(yaw) * 0.68D;
        double leftZ = Math.sin(yaw) * 0.68D;
        // 始终悬浮在玩家头部左侧，模型中心与玩家头部等高。
        moveTo(owner.getX() + leftX, owner.getEyeY() - 0.24D,
                owner.getZ() + leftZ, owner.getYRot(), 0.0F);
    }

    @Override public boolean hurt(DamageSource source, float amount) { return false; }
    @Override public void setHealth(float health) { super.setHealth(getMaxHealth()); }
    @Override public void die(DamageSource source) {}
    @Override public void kill() {}
    @Override public boolean addEffect(MobEffectInstance effect, Entity source) { return false; }
    @Override public HumanoidArm getMainArm() { return HumanoidArm.RIGHT; }
    @Override public boolean canBeHitByProjectile() { return false; }
    @Override public boolean isPickable() { return false; }
    @Override public boolean isPushable() { return false; }
    @Override public void push(Entity entity) {}
    @Override public void push(double x, double y, double z) {}
    @Override public boolean isAttackable() { return false; }
    @Override public boolean shouldBeSaved() { return false; }

    @Override public void readAdditionalSaveData(CompoundTag tag) {
        if (tag.hasUUID("Owner")) ownerUuid = tag.getUUID("Owner");
    }
    @Override public void addAdditionalSaveData(CompoundTag tag) {
        if (ownerUuid != null) tag.putUUID("Owner", ownerUuid);
    }

    @Override public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "main", 0, state -> state.setAndContinue(
                RawAnimation.begin().thenLoop("fly"))));
    }
    @Override public AnimatableInstanceCache getAnimatableInstanceCache() { return cache; }
    @Override public double getTick(Object object) { return tickCount; }
}
