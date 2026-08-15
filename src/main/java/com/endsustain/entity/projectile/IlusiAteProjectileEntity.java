package com.endsustain.entity.projectile;

import com.endsustain.entity.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

public class IlusiAteProjectileEntity extends Projectile {
    @Nullable private LivingEntity ownerLiving;
    private boolean spawned;

    public IlusiAteProjectileEntity(EntityType<? extends IlusiAteProjectileEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    public IlusiAteProjectileEntity(Level level, LivingEntity owner) {
        this(ModEntities.ILUSI_ATE_PROJECTILE.get(), level);
        this.ownerLiving = owner;
        this.setOwner(owner);
        this.setPos(owner.getX(), owner.getEyeY() - 0.1D, owner.getZ());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        if (this.ownerLiving == null) {
            Entity owner = this.getOwner();
            if (owner instanceof LivingEntity living) this.ownerLiving = living;
        }

        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS) {
            this.onHit(hit);
            return;
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.04D, 0.0D).scale(0.99D));
        rotateToMovement();
        this.hurtMarked = true;

        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.FLAME,
                    this.getX(), this.getY(), this.getZ(), 1, 0.05D, 0.05D, 0.05D, 0.0D);
        }
        if (this.tickCount > 100 || this.getY() < this.level().getMinBuildHeight() - 16) {
            burstAndDiscard();
        }
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (!this.level().isClientSide) {
            burstAndDiscard();
        }
    }

    private void burstAndDiscard() {
        if (this.spawned) return;
        this.spawned = true;
        if (this.level() instanceof ServerLevel server) {
            Vec3 center = this.position();
            server.sendParticles(ParticleTypes.EXPLOSION,
                    center.x, center.y, center.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
            server.sendParticles(ParticleTypes.CRIT,
                    center.x, center.y, center.z, 24, 0.45D, 0.25D, 0.45D, 0.15D);
            for (int i = 0; i < 3; i++) {
                IlusiHomingTridentEntity trident = new IlusiHomingTridentEntity(server, this.ownerLiving);
                double angle = (Math.PI * 2.0D / 3.0D) * i;
                Vec3 offset = new Vec3(Math.cos(angle) * 0.8D, 0.45D, Math.sin(angle) * 0.8D);
                trident.setPos(center.x + offset.x, center.y + offset.y, center.z + offset.z);
                trident.setDeltaMovement(offset.normalize().add(0.0D, 0.35D, 0.0D).normalize().scale(1.4D));
                server.addFreshEntity(trident);
            }
        }
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
        return entity != this.getOwner() && super.canHitEntity(entity);
    }

    @Override
    protected void defineSynchedData() {}
}
