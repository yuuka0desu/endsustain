package com.endsustain.pigblade;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.level.Level;

/**
 * 猪形态替身：继承原实体血量/护甲/抗性，行为完全按猪执行。
 * 超过 5 秒未死亡时自动还原原形态并结算超时惩罚。
 */
public class PigFormPig extends Pig {
    public PigFormPig(EntityType<? extends PigFormPig> type, Level level) {
        super(type, level);
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.level().isClientSide
                && getPersistentData().contains("EndsustainPigFormExpireAt")
                && this.level().getGameTime() >= getPersistentData().getLong("EndsustainPigFormExpireAt")) {
            PigFormLogic.revert(this, true);
        }
    }

    /** 猪形态不提供任何掉落与经验：奖励由还原后的原实体结算。 */
    @Override
    public boolean shouldDropExperience() {
        return false;
    }

    @Override
    public int getExperienceReward() {
        return 0;
    }
}
