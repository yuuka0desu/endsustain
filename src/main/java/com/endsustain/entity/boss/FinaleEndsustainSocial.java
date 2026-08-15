package com.endsustain.entity.boss;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

final class FinaleEndsustainSocial {
    private FinaleEndsustainSocial() {}

    static void tick(FinaleEndsustainEntity boss) {
        if (boss.getSocialPhase() == FinaleEndsustainEntity.PHASE_HOSTILE) return;
        float health = boss.getHealth();
        if (health < boss.getObservedHealthInternal()) {
            boss.enterHostileInternal();
            return;
        }
        boss.setObservedHealthInternal(health);
        if (boss.getSocialPhase() == FinaleEndsustainEntity.PHASE_NEUTRAL) {
            if (boss.hasHitWithoutDamageInternal()) {
                boss.incrementNeutralHurtInternal();
                if (boss.getNeutralHurtInternal() >= 400) boss.enterSleepingInternal();
            } else if (boss.incrementNeutralIdleInternal() >= 200) {
                boss.enterSleepingInternal();
            }
        }
    }

    static void summon(FinaleEndsustainEntity boss) {
        ServerLevel level = (ServerLevel) boss.level();
        Player target = boss.findNearestPlayerInFollowRange();
        if (target == null) return;
        for (int i = 0; i < 3; i++) {
            QunUEntity qun = new QunUEntity(com.endsustain.entity.ModEntities.QUN_U.get(), level);
            qun.moveTo(boss.getX() + (i - 1) * 1.3D, boss.getY(), boss.getZ() + 1.2D);
            qun.bind(boss, target);
            level.addFreshEntity(qun);
            boss.trackQunU(qun.getUUID());
        }
    }
}
