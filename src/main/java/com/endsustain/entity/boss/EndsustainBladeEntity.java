package com.endsustain.entity.boss;

import com.endsustain.combat.TrueKillUtil;
import com.endsustain.entity.ModEntities;
import com.endsustain.item.ModItems;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ItemSupplier;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;

import javax.annotation.Nullable;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class EndsustainBladeEntity extends Projectile implements ItemSupplier {
    private static final double HOMING_STRENGTH = 0.22D;
    private static final String CARRIED_STACK_KEY = "CarriedBlade";

    @Nullable private LivingEntity ownerLiving;
    @Nullable private LivingEntity trackedTarget;
    private ItemStack carriedStack = ItemStack.EMPTY;
    @Nullable private UUID ownerPlayerUuid;
    private boolean returning;
    private boolean playerChargedThrow;
    private int returnCooldown;
    private int returnTicks;
    private int missingOwnerTicks;
    private final Set<UUID> pathKilledEntities = new HashSet<>();

    public EndsustainBladeEntity(EntityType<? extends EndsustainBladeEntity> type, Level level) {
        super(type, level);
        this.noCulling = true;
    }

    public EndsustainBladeEntity(Level level, FinaleEndsustainEntity owner, LivingEntity target) {
        this(ModEntities.ENDSUSTAIN_BLADE_ENTITY.get(), level);
        this.ownerLiving = owner;
        this.trackedTarget = target;
        this.setOwner(owner);
        this.setPos(owner.getX(), owner.getEyeY() - 0.15D, owner.getZ());
        this.setDeltaMovement(target.getEyePosition().subtract(this.position()).normalize().scale(2.8D));
    }

    public EndsustainBladeEntity(Level level, Player owner, ItemStack carriedStack) {
        this(ModEntities.ENDSUSTAIN_BLADE_ENTITY.get(), level);
        this.ownerLiving = owner;
        this.ownerPlayerUuid = owner.getUUID();
        this.playerChargedThrow = true;
        this.carriedStack = carriedStack.copy();
        this.setOwner(owner);
        this.setPos(owner.getX(), owner.getEyeY() - 0.15D, owner.getZ());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) return;

        if (this.ownerLiving == null) {
            Entity realOwner = this.getOwner();
            if (realOwner instanceof LivingEntity living) this.ownerLiving = living;
        }
        if (this.ownerLiving == null && this.ownerPlayerUuid != null && this.level() instanceof ServerLevel server) {
            ServerPlayer player = server.getServer().getPlayerList().getPlayer(this.ownerPlayerUuid);
            if (player != null) {
                if (player.level() != this.level()) {
                    returnToOwnerInventory(player);
                    this.discard();
                    return;
                }
                this.ownerLiving = player;
                this.setOwner(player);
            }
        }
        if (this.ownerLiving == null) {
            this.missingOwnerTicks++;
            if (this.missingOwnerTicks > 600) {
                dropCarriedStack();
                this.discard();
            }
            return;
        }
        this.missingOwnerTicks = 0;
        if (!this.ownerLiving.isAlive()) {
            if (this.ownerLiving instanceof Player player) {
                returnToOwnerInventory(player);
            } else {
                dropCarriedStack();
            }
            this.discard();
            return;
        }

        // 忠诚回收兜底：飞出 50 格、跌至 Y<=-64 或跨维度时直接放回玩家物品栏。
        if (this.ownerLiving instanceof Player player
                && (player.level() != this.level()
                || this.distanceToSqr(player) > 2500.0D
                || this.getY() <= -64.0D)) {
            returnToOwnerInventory(player);
            this.discard();
            return;
        }

        if (this.returning) {
            tickReturn();
        } else {
            if (this.trackedTarget != null && this.trackedTarget.isAlive()) homeToTarget();
            killAlongChargedPath();
            HitResult hit = ProjectileUtil.getHitResultOnMoveVector(this, this::canHitEntity);
            if (hit.getType() != HitResult.Type.MISS) {
                this.onHit(hit);
                return;
            }
            // 玩家投掷的终焉之刃飞行超过 30 秒后，直接强制放回物品栏。
            if (this.playerChargedThrow && this.tickCount > 600 && this.ownerLiving instanceof Player player) {
                returnToOwnerInventory(player);
                this.discard();
                return;
            }
            // Boss 发射的刀保留原有生命周期。
            if (!this.playerChargedThrow && this.tickCount > 120) beginReturn(0);
        }

        this.move(MoverType.SELF, this.getDeltaMovement());
        rotateToMovement();
        this.hurtMarked = true;
    }

    private void homeToTarget() {
        if (this.trackedTarget == null) return;
        Vec3 motion = this.getDeltaMovement();
        Vec3 currentDir = motion.lengthSqr() > 1.0E-6D ? motion.normalize() : new Vec3(0.0D, 0.0D, 1.0D);
        Vec3 toTarget = this.trackedTarget.getEyePosition().subtract(this.position()).normalize();
        Vec3 newDir = slerp(currentDir, toTarget, HOMING_STRENGTH).normalize();
        this.setDeltaMovement(newDir.scale(Math.max(2.8D, motion.length())));
    }

    private void killAlongChargedPath() {
        if (!this.playerChargedThrow || !(this.ownerLiving instanceof Player owner)) return;
        Vec3 start = this.position();
        Vec3 end = start.add(this.getDeltaMovement());
        AABB path = new AABB(start, end).inflate(6.0D);
        for (LivingEntity target : this.level().getEntitiesOfClass(LivingEntity.class, path,
                living -> living.isAlive()
                        && !(living instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity)
                        && living != owner

                        && !living.getUUID().equals(owner.getUUID())
                        && !this.pathKilledEntities.contains(living.getUUID()))) {
            this.pathKilledEntities.add(target.getUUID());
            boolean overflow = owner instanceof ServerPlayer serverPlayer
                    && com.endsustain.item.weapon.EndsustainBladeItem.hasOverflowDamage(serverPlayer);
            TrueKillUtil.forceKill(target, this.damageSources().fellOutOfWorld(), owner,
                    (float) Integer.MAX_VALUE, overflow);
        }
    }

    private static Vec3 slerp(Vec3 a, Vec3 b, double t) {
        double dot = Math.max(-0.999D, Math.min(0.999D, a.dot(b)));
        double theta = Math.acos(dot) * t;
        Vec3 perpendicular = b.subtract(a.scale(dot));
        if (perpendicular.lengthSqr() < 1.0E-6D) return b;
        return a.scale(Math.cos(theta)).add(perpendicular.normalize().scale(Math.sin(theta)));
    }

    private void rotateToMovement() {
        Vec3 motion = this.getDeltaMovement();
        if (motion.lengthSqr() < 1.0E-6D) return;
        Vec3 dir = motion.normalize();
        this.setYRot((float)(Math.atan2(dir.x, dir.z) * (180.0D / Math.PI)));
        this.setXRot((float)(Math.asin(-dir.y) * (180.0D / Math.PI)));
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity hit = result.getEntity();
        if (hit == this.ownerLiving || hit instanceof com.endsustain.entity.companion.SmallZhanjiangCompanionEntity) {
            beginReturn(2);
            return;
        }

        float damage = this.ownerLiving instanceof FinaleEndsustainEntity
                ? (hit instanceof Player ? 42.0F : 28.0F)
                : 1.0F;
        hit.invulnerableTime = 0;
        if (this.ownerLiving instanceof Player) {
            hit.hurt(this.damageSources().trident(this, this.ownerLiving), damage);
        } else {
            hit.hurt(this.damageSources().mobAttack(this.ownerLiving), damage);
        }
        hit.invulnerableTime = 0;
        beginReturn(2);
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        beginReturn(2);
    }

    private void beginReturn(int delay) {
        this.returning = true;
        this.returnCooldown = delay;
        this.returnTicks = 0;
        this.setDeltaMovement(Vec3.ZERO);
        this.noPhysics = true;
    }

    private void tickReturn() {
        this.returnTicks++;
        if (this.returnCooldown > 0) {
            this.returnCooldown--;
            return;
        }
        if (this.ownerLiving == null || !this.ownerLiving.isAlive()) {
            dropCarriedStack();
            this.discard();
            return;
        }
        Vec3 destination = this.ownerLiving.getEyePosition().subtract(0.0D, 0.25D, 0.0D);
        Vec3 toOwner = destination.subtract(this.position());
        // 扩大近身吸附范围，并用 40 tick 超时兜底，消除玩家身边来回抽搐。
        if (toOwner.lengthSqr() < 16.0D || this.returnTicks > 40) {
            returnToOwnerInventory();
            this.discard();
            return;
        }
        this.setDeltaMovement(toOwner.normalize().scale(3.2D));
    }

    private void returnToOwnerInventory() {
        if (this.ownerLiving instanceof Player player) {
            returnToOwnerInventory(player);
        }
    }

    private void returnToOwnerInventory(Player player) {
        if (this.carriedStack.isEmpty()) return;
        ItemStack returned = this.carriedStack.copy();
        this.carriedStack = ItemStack.EMPTY;
        if (player.getMainHandItem().isEmpty()) {
            player.setItemSlot(EquipmentSlot.MAINHAND, returned);
        } else if (!player.getInventory().add(returned)) {
            player.drop(returned, false);
        }
    }

    private void dropCarriedStack() {
        if (!this.carriedStack.isEmpty() && !this.level().isClientSide) {
            this.spawnAtLocation(this.carriedStack.copy());
            this.carriedStack = ItemStack.EMPTY;
        }
    }

    @Override
    public ItemStack getItem() {
        return new ItemStack(ModItems.ENDSUSTAIN_BLADE.get());
    }

    @Override
    public boolean hurt(net.minecraft.world.damagesource.DamageSource source, float amount) {
        return false;
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isNoGravity() {
        return true;
    }

    @Override
    protected boolean canHitEntity(Entity entity) {
        return entity != this.ownerLiving && super.canHitEntity(entity);
    }

    @Override
    protected void defineSynchedData() {}

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putBoolean("Returning", this.returning);
        tag.putBoolean("PlayerChargedThrow", this.playerChargedThrow);
        tag.putInt("ReturnCooldown", this.returnCooldown);
        tag.putInt("ReturnTicks", this.returnTicks);
        tag.putInt("MissingOwnerTicks", this.missingOwnerTicks);
        if (this.ownerPlayerUuid != null) {
            tag.putUUID("OwnerPlayer", this.ownerPlayerUuid);
        }
        if (!this.carriedStack.isEmpty()) {
            tag.put(CARRIED_STACK_KEY, this.carriedStack.save(new CompoundTag()));
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        this.returning = tag.getBoolean("Returning");
        this.playerChargedThrow = tag.getBoolean("PlayerChargedThrow");
        this.returnCooldown = tag.getInt("ReturnCooldown");
        this.returnTicks = tag.getInt("ReturnTicks");
        this.missingOwnerTicks = tag.getInt("MissingOwnerTicks");
        if (tag.hasUUID("OwnerPlayer")) {
            this.ownerPlayerUuid = tag.getUUID("OwnerPlayer");
        }
        if (tag.contains(CARRIED_STACK_KEY, CompoundTag.TAG_COMPOUND)) {
            this.carriedStack = ItemStack.of(tag.getCompound(CARRIED_STACK_KEY));
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }
}
