package com.endsustain.mixin;

import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * 核心劫持：数据项写入通道。
 * 一切生命存储（含 Boss 覆写 getHealth 绕过的核心字段）最终都经
 * SynchedEntityData.set 写入。对终焉标记实体，任何 Float/Double 数据项的
 * 正数写入被改写为 0 后放行——写入照常发生但值恒为 0，
 * 虚拟恢复/回滚/写回在存储层被彻底封锁。
 * 返回类型与入参类型保持一致（Float→Float，Double→Double），绝不跨类型改写；
 * Float 槽的类型污染修复由 EntityHealthStateBridge 按 serializer 声明类型完成。
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    @Shadow
    @Final
    private Entity f_135344_;

    @ModifyVariable(method = "m_135381_", at = @At("HEAD"), argsOnly = true, index = 2)
    private Object endsustain$zeroNumericWrites(Object value) {
        if (!(f_135344_ instanceof LivingEntity living)) return value;
        // 权威判定在模组私有表：目标 remove persistentData 键无法拆除本拦截
        if (!com.endsustain.combat.TerminalStateRegistry.isMarked(living)
                || !com.endsustain.combat.TerminalStateRegistry.isHijacked(living)) {
            return value;
        }
        if (value instanceof Double d && d > 0.0D) {
            return Double.valueOf(0.0D);
        }
        if (value instanceof Float f && f > 0.0F) {
            return Float.valueOf(0.0F);
        }
        return value;
    }
}
