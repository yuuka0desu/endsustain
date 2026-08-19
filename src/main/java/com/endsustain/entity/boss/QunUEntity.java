package com.endsustain.entity.boss;

import net.minecraft.network.chat.Component;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import java.util.UUID;

public class QunUEntity extends Pig {
    private UUID ownerBoss;
    private UUID targetPlayer;
    private int attackCooldown;

    public QunUEntity(EntityType<? extends QunUEntity> type, Level level) {
        super(type, level);
        setCustomName(Component.literal("群u"));
        setCustomNameVisible(true);
        getPersistentData().putBoolean("EndsustainQunU", true);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Pig.createAttributes().add(Attributes.MAX_HEALTH, 10000.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.35D).add(Attributes.ATTACK_DAMAGE, 0.0D)
                .add(Attributes.FOLLOW_RANGE, 48.0D).add(Attributes.KNOCKBACK_RESISTANCE, 0.2D);
    }

    @Override protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.25D, true));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 12.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
    }

    public void bind(FinaleEndsustainEntity boss, Player player) {
        ownerBoss = boss.getUUID(); targetPlayer = player == null ? null : player.getUUID();
        getPersistentData().putUUID("EndsustainOwner", ownerBoss);
        if (targetPlayer != null) getPersistentData().putUUID("EndsustainTarget", targetPlayer);
        setTarget(player);
    }
    public UUID getOwnerBoss() { return ownerBoss; }

    @Override public void aiStep() {
        super.aiStep();
        if (attackCooldown > 0) attackCooldown--;
        if (level().isClientSide) return;
        if (!getPersistentData().getBoolean("EndsustainAllChampionAffixes")
                && com.endsustain.compat.champions.ChampionsCompat.applyAllAffixes(this)) {
            getPersistentData().putBoolean("EndsustainAllChampionAffixes", true);
            setHealth(getMaxHealth());
        }
        Player target = findTarget();
        if (target == null) return;
        setTarget(target);
        if (distanceToSqr(target) <= 2.25D && attackCooldown <= 0) {
            setDeltaMovement(getDeltaMovement().add(0.0D, 0.45D, 0.0D));
            hurtMarked = true;
            target.hurt(damageSources().fellOutOfWorld(), 20.0F);
            attackCooldown = 30;
        }
    }

    private Player findTarget() {
        if (targetPlayer != null && level() instanceof ServerLevel server) {
            var e = server.getEntity(targetPlayer);
            if (e instanceof Player p && p.isAlive() && !p.isSpectator()) return p;
        }
        return level().getEntitiesOfClass(Player.class, getBoundingBox().inflate(48),
                p -> p.isAlive() && !p.isSpectator()).stream().findFirst().orElse(null);
    }

    @Override public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (ownerBoss != null) tag.putUUID("EndsustainOwner", ownerBoss);
        if (targetPlayer != null) tag.putUUID("EndsustainTarget", targetPlayer);
        tag.putInt("AttackCooldown", attackCooldown);
    }
    @Override public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        ownerBoss = tag.hasUUID("EndsustainOwner") ? tag.getUUID("EndsustainOwner") : null;
        targetPlayer = tag.hasUUID("EndsustainTarget") ? tag.getUUID("EndsustainTarget") : null;
        attackCooldown = tag.getInt("AttackCooldown");
    }
}
