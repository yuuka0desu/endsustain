package com.endsustain.entity.projectile;

import com.endsustain.entity.ModEntities;
import com.endsustain.network.EndSustainNetwork;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * 无抛物线直线投射物：不造成任何血量伤害，
 * 仅对飞行路径上的玩家（排除投掷者本人）投递双重崩溃信号。
 */
public class NullVoidProjectileEntity extends ThrowableProjectile {
    private static final int MAX_LIFETIME = 100;

    public NullVoidProjectileEntity(EntityType<NullVoidProjectileEntity> type, Level level) {
        super(type, level);
    }

    public NullVoidProjectileEntity(Level level, LivingEntity owner) {
        super(ModEntities.NULL_VOID_PROJECTILE.get(), owner, level);
        setOwner(owner);
        setNoGravity(true);
        setPos(owner.getEyePosition().x, owner.getEyePosition().y - 0.1D, owner.getEyePosition().z);
        shootFromRotation(owner, owner.getXRot(), owner.getYRot(), 0.0F, 3.2F, 0.0F);
    }

    @Override
    protected void defineSynchedData() {}

    /** 无抛物线：重力恒为 0。 */
    @Override
    protected float getGravity() {
        return 0.0F;
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide) {
            // 授权校验下沉到实体层：任何途径生成的实体，owner 未解锁立即失效。
            if (!(getOwner() instanceof net.minecraft.world.entity.player.Player owner)
                    || !com.endsustain.item.NullVoidBladeItem.isUnlocked(owner)) {
                discard();
                return;
            }
            boolean consumed = consumePathTargets();
            if (consumed) {
                discard();
                return;
            }
        }
        move(MoverType.SELF, getDeltaMovement());
        if (tickCount > MAX_LIFETIME) discard();
    }

    /** 扫描当前移动向量路径上的玩家并投递崩溃信号；返回是否已消耗。 */
    private boolean consumePathTargets() {
        Vec3 start = position();
        Vec3 end = start.add(getDeltaMovement());
        net.minecraft.world.phys.AABB path = new net.minecraft.world.phys.AABB(start, end).inflate(1.0D);
        boolean consumed = false;
        for (ServerPlayer target : level().getEntitiesOfClass(ServerPlayer.class, path,
                p -> p.isAlive() && p != getOwner() && !p.isSpectator())) {
            EndSustainNetwork.sendNullCrash(target);
            EndSustainNetwork.sendAbnormalState(target);
            EndSustainNetwork.sendNullPulse(target);
            consumed = true;
        }
        return consumed;
    }

    @Override
    protected void onHitBlock(net.minecraft.world.phys.BlockHitResult result) {
        discard();
    }

    @Override
    protected void onHitEntity(net.minecraft.world.phys.EntityHitResult result) {
        if (result.getEntity() == getOwner()) return;
        if (!level().isClientSide && result.getEntity() instanceof ServerPlayer player) {
            EndSustainNetwork.sendNullCrash(player);
            EndSustainNetwork.sendAbnormalState(player);
            EndSustainNetwork.sendNullPulse(player);
        }
        discard();
    }

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {}

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return new ClientboundAddEntityPacket(this);
    }
}
