package com.endsustain.entity.projectile;

import com.endsustain.entity.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public class IlusiHomingTridentEntity extends Projectile {
    private static final double SEARCH_RANGE = 48.0D;
    private static final double SPEED = 2.0D;
    private static final double HOMING_STRENGTH = 0.62D;
    private static final double PROXIMITY_HIT_RANGE = 1.75D;
    private static final float DAMAGE = 18.0F;

    @Nullable private LivingEntity ownerLiving;
    @Nullable private LivingEntity target;

    public IlusiHomingTridentEntity(EntityType<? extends IlusiHomingTridentEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
        this.noPhysics = true;
    }

    public IlusiHomingTridentEntity(Level level, @Nullable LivingEntity owner) {
        this(ModEntities.ILUSI_HOMING_TRIDENT.get(), level);
        this.ownerLiving = owner;
        if (owner != null) this.setOwner(owner);
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        if (this.ownerLiving == null) {
            Entity owner = this.getOwner();
            if (owner instanceof LivingEntity living) this.ownerLiving = living;
        }

        if (this.target == null || !this.target.isAlive() || this.target.isSpectator()) {
            this.target = findNearestHostile();
        }
        if (this.target != null) {
            homeToTarget();
            if (this.distanceToSqr(this.target) <= PROXIMITY_HIT_RANGE * PROXIMITY_HIT_RANGE) {
                this.onHitEntity(new EntityHitResult(this.target));
                return;
            }
        } else if (this.getDeltaMovement().lengthSqr() < 0.01D) {
            this.setDeltaMovement(0.0D, 0.2D, 0.0D);
        }

        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit instanceof EntityHitResult entityHit) {
            this.onHitEntity(entityHit);
            return;
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        rotateToMovement();
        this.hurtMarked = true;

        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.ENCHANTED_HIT,
                    this.getX(), this.getY(), this.getZ(), 2, 0.05D, 0.05D, 0.05D, 0.02D);
            server.sendParticles(ParticleTypes.CRIT,
                    this.getX(), this.getY(), this.getZ(), 1, 0.02D, 0.02D, 0.02D, 0.0D);
        }

        if (this.tickCount > 160 || this.getY() < this.level().getMinBuildHeight() - 16) {
            this.discard();
        }
    }

    @Nullable
    private LivingEntity findNearestHostile() {
        if (!(this.level() instanceof ServerLevel server)) return null;
        return server.getEntitiesOfClass(LivingEntity.class,
                        this.getBoundingBox().inflate(SEARCH_RANGE),
                        entity -> entity instanceof Monster
                                && entity.isAlive()
                                && !entity.isSpectator()
                                && entity != this.ownerLiving)
                .stream()
                .min(java.util.Comparator.comparingDouble(this::distanceToSqr))
                .orElse(null);
    }

    private void homeToTarget() {
        if (this.target == null) return;
        Vec3 current = this.getDeltaMovement();
        Vec3 currentDir = current.lengthSqr() > 1.0E-6D ? current.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
        double distance = this.distanceTo(this.target);
        double leadTicks = Math.min(6.0D, distance / SPEED * 0.35D);
        Vec3 predictedPosition = this.target.getEyePosition()
                .add(this.target.getDeltaMovement().scale(leadTicks));
        Vec3 toTarget = predictedPosition.subtract(this.position()).normalize();
        Vec3 newDir = currentDir.scale(1.0D - HOMING_STRENGTH)
                .add(toTarget.scale(HOMING_STRENGTH))
                .normalize();
        this.setDeltaMovement(newDir.scale(SPEED));
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity hit = result.getEntity();
        if (hit == this.ownerLiving) return;
        if (hit instanceof LivingEntity living) {
            living.invulnerableTime = 0;
            Entity owner = this.ownerLiving != null ? this.ownerLiving : this;
            living.hurt(this.damageSources().trident(this, owner), DAMAGE);
            living.invulnerableTime = 0;
            if (this.level() instanceof ServerLevel server) {
                server.sendParticles(ParticleTypes.CRIT,
                        this.getX(), this.getY(), this.getZ(), 16, 0.25D, 0.25D, 0.25D, 0.18D);
            }
        }
        this.discard();
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        this.discard();
    }

    private void rotateToMovement() {
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-6D) return;
        Vec3 dir = motion.normalize();
        this.setYRot((float)(Math.atan2(dir.x, dir.z) * (180.0D / Math.PI)));
        this.setXRot((float)(Math.asin(-dir.y) * (180.0D / Math.PI)));
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity != this.ownerLiving && super.canHitEntity(entity);
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    protected void defineSynchedData() {}
}
