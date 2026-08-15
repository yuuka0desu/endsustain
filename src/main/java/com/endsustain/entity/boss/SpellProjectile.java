package com.endsustain.entity.boss;

import com.endsustain.entity.ModEntities;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 通用法术弹射物 —— 铁魔法 / 诡厄巫法的可见弹射体。
 * 制导追踪目标，命中造成伤害 + 粒子爆裂。
 */
public class SpellProjectile extends Projectile {

    @Nullable private FinaleEndsustainEntity owner;
    @Nullable private LivingEntity target;
    private final float damage;
    private final float impactRadius;
    private int life;

    public SpellProjectile(Level level, FinaleEndsustainEntity owner, LivingEntity target, float damage) {
        this(level, owner, target, damage, 0.0F);
    }

    public SpellProjectile(Level level, FinaleEndsustainEntity owner, LivingEntity target, float damage, float impactRadius) {
        super(ModEntities.SPELL_PROJECTILE.get(), level);
        this.owner = owner;
        this.target = target;
        this.damage = damage;
        this.impactRadius = impactRadius;
        this.noCulling = true;
    }

    public SpellProjectile(EntityType<? extends SpellProjectile> type, Level level) {
        super(type, level);
        this.damage = 1.0F;
        this.impactRadius = 0.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        this.setPos(this.getX() + this.getDeltaMovement().x,
                this.getY() + this.getDeltaMovement().y,
                this.getZ() + this.getDeltaMovement().z);
        if (this.impactRadius > 0.0F) {
            this.setDeltaMovement(this.getDeltaMovement().add(0.0D, -0.06D, 0.0D).scale(1.01D));
        }

        if (this.target != null && this.target.isAlive() && !this.target.isSpectator()) {
            Vec3 to = this.target.getEyePosition().subtract(this.position()).normalize();
            this.setDeltaMovement(to.scale(this.getDeltaMovement().length()));
        }

        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.WITCH,
                    this.getX(), this.getY(), this.getZ(), 1, 0.1, 0.1, 0.1, 0);
        }

        HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
        if (hit.getType() != HitResult.Type.MISS) this.onHit(hit);
        if (++this.life > 80) this.discard();
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity hit = result.getEntity();
        if (hit == this.owner) return;
        hit.invulnerableTime = 0;
        hit.hurt(this.damageSources().indirectMagic(this, this.owner), this.damage);
        hit.invulnerableTime = 0;
        burst();
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        burst();
    }

    private void burst() {
        if (this.level() instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.PORTAL,
                    this.getX(), this.getY(), this.getZ(), 12, 0.4, 0.4, 0.4, 0.2);
            if (this.impactRadius > 0.0F) {
                server.sendParticles(ParticleTypes.EXPLOSION,
                        this.getX(), this.getY(), this.getZ(), 1, 0.0, 0.0, 0.0, 0.0);
                var box = this.getBoundingBox().inflate(this.impactRadius);
                for (LivingEntity living : server.getEntitiesOfClass(LivingEntity.class, box,
                        e -> e.isAlive() && e != this.owner && !e.isSpectator())) {
                    double distance = living.distanceTo(this);
                    if (distance <= this.impactRadius) {
                        float scaledDamage = this.damage * (float)Math.max(0.35D, 1.0D - distance / this.impactRadius);
                        living.invulnerableTime = 0;
                        living.hurt(this.damageSources().indirectMagic(this, this.owner), scaledDamage);
                        living.invulnerableTime = 0;
                    }
                }
            }
        }
        this.discard();
    }

    @Override
    protected void defineSynchedData() {}
}
