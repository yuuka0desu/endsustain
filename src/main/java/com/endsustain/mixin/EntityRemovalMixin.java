package com.endsustain.mixin;

import com.endsustain.combat.TerminalRemovalAccess;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;

/**
 * 审计 P1：removalReason 字段直达。
 * - removalReason：SRG f_146795_（Mojmap removalReason）
 * - levelCallback：SRG f_146801_（Mojmap levelCallback，类型 EntityInLevelCallback）
 * - onRemove(RemovalReason)：Mojmap onRemove → SRG m_142472_（refmap 自动映射）
 * 1.20.1 中 setRemoved 为 final（m_142467_），实体实际覆写的是 remove（m_142687_）；
 * 本 mixin 直写字段 + 补全原版摘除语义，绕开一切 remove/setRemoved 覆写。
 */
@Mixin(Entity.class)
public abstract class EntityRemovalMixin implements TerminalRemovalAccess {

    @Shadow
    private Entity.RemovalReason f_146795_;

    @Shadow
    private EntityInLevelCallback f_146801_;

    @Override
    public void endsustain$forceRemoval(boolean killed) {
        Entity self = (Entity) (Object) this;
        if (this.f_146795_ != null) return; // 单次执行，防重复
        this.f_146795_ = killed ? Entity.RemovalReason.KILLED : Entity.RemovalReason.DISCARDED;
        // 原版 setRemoved 语义：乘客分离 + 从世界结构摘除
        for (Entity passenger : new ArrayList<>(self.getPassengers())) {
            passenger.stopRiding();
        }
        if (this.f_146801_ != null) {
            this.f_146801_.onRemove(this.f_146795_);
        }
        try {
            self.invalidateCaps(); // Forge patch 方法，生产保留原名；保险隔离
        } catch (Throwable ignored) {
        }
        // 不触发 gameEvent(ENTITY_DIE)：die() 已触发过，避免 sculk/监守者双触发
    }
}
