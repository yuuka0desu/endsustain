package com.endsustain.mixin;

import com.endsustain.combat.EndsustainTerminal;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArgs;
import org.spongepowered.asm.mixin.injection.invoke.arg.Args;

/**
 * 核心劫持：数据项写入通道。
 * 一切生命存储（含 Boss 覆写 getHealth 绕过的核心字段）最终都经
 * SynchedEntityData.set 写入。对终焉标记实体：
 * 1) 类型守卫：按 accessor 声明的 serializer 强制写入类型——
 *    Double 写进 Float 槽（或反之）会被纠正，杜绝后续读取时
 *    ClassCastException（tutm/goety 等对 Float 槽做 (Float) 强转）。
 * 2) 正数归零：Float/Double 槽的正数写入被改写为 0 后放行——
 *    写入照常发生但值恒为 0，虚拟恢复/回滚/写回在存储层被彻底封锁。
 */
@Mixin(SynchedEntityData.class)
public abstract class SynchedEntityDataMixin {

    @Shadow
    @Final
    private Entity f_135344_;

    @ModifyArgs(method = "m_135381_", at = @At("HEAD"))
    private void endsustain$zeroNumericWrites(Args args) {
        if (!(f_135344_ instanceof LivingEntity living)) return;
        if (!living.getPersistentData().getBoolean(EndsustainTerminal.TERMINAL_KEY)
                || !living.getPersistentData().getBoolean(EndsustainTerminal.HEALTH_HIJACK_KEY)) {
            return;
        }
        Object accessorObj = args.get(0);
        Object value = args.get(1);
        if (!(accessorObj instanceof EntityDataAccessor<?> accessor)) return;
        if (!(value instanceof Number number)) return;

        Object serializer = accessor.getSerializer();
        double raw = number.doubleValue();

        if (serializer == EntityDataSerializers.FLOAT) {
            if (value instanceof Double) {
                // 类型污染纠正：Double 写进 Float 槽 → 强制转 Float
                args.set(1, Float.valueOf((float) Math.max(0.0D, Math.min(raw, (double) Float.MAX_VALUE))));
                return;
            }
            if (raw > 0.0D) args.set(1, 0.0F);
            return;
        }
        if (serializer == EntityDataSerializers.DOUBLE) {
            if (value instanceof Float) {
                // 类型污染纠正：Float 写进 Double 槽 → 强制转 Double
                args.set(1, Double.valueOf(Math.max(0.0D, raw)));
                return;
            }
            if (raw > 0.0D) args.set(1, 0.0D);
            return;
        }
        // 其余数值类型槽（Int/Long/Short/Byte）：正数归零，保持原类型
        if (raw > 0.0D) {
            if (value instanceof Float) args.set(1, 0.0F);
            else if (value instanceof Double) args.set(1, 0.0D);
            else if (value instanceof Integer) args.set(1, 0);
            else if (value instanceof Long) args.set(1, 0L);
            else if (value instanceof Short) args.set(1, (short) 0);
            else if (value instanceof Byte) args.set(1, (byte) 0);
        }
    }
}
