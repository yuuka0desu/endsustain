package com.endsustain.entity.boss;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Boss 落幕之终焉·末影蘸酱的专属战斗 AI。<p>
 * 按血量百分比与冷却时间调度三套技能组：<ul>
 *   <li>铁魔法技能组 —— 远程施法</li>
 *   <li>诡厄巫法聚晶组 —— 中距离控制</li>
 *   <li>终焉之刃投掷 —— 近距离物理（锁定追踪）</li>
 *   <li>必杀技 —— 血量 < 15% 触发一次</li>
 * </ul>
 * 每个技能释放前都会调用 {@code boss.lockOntoPlayer(target)} 强制面朝
 * 并短暂固定玩家坐标，确保技能不会 miss。
 */
public class FinaleCombatGoal extends Goal {

    private final FinaleEndsustainEntity boss;
    private Player target;
    private int cooldown;
    private int lockTicks;        // 锁定剩余 tick（释放前蓄力）
    private boolean skillQueued;  // 是否有排队的技能待释放
    private String queuedIronSpell;
    private String queuedCrystal;
    private boolean queuedBlade;
    private boolean queuedCharm;
    private static final int SIMULTANEOUS_SKILL_CASTS = 3;
    private static final int SKILL_COOLDOWN_TICKS = 7; // 约为原 20 tick 间隔的 300% 频率

    public FinaleCombatGoal(FinaleEndsustainEntity boss) {
        this.boss = boss;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Override
    public boolean canUse() {
        LivingEntity t = this.boss.getTarget();
        if (t instanceof Player p && p.isAlive()) {
            this.target = p;
            return true;
        }
        Player nearest = this.boss.findNearestPlayerInFollowRange();
        if (nearest != null) {
            this.target = nearest;
            this.boss.setTarget(nearest);
            return true;
        }
        return false;
    }

    @Override
    public boolean canContinueToUse() {
        int st = this.boss.getAttackState();
        boolean ultRunning = st == FinaleEndsustainEntity.STATE_ULT_RAISE
                          || st == FinaleEndsustainEntity.STATE_ULT_DASH
                          || st == FinaleEndsustainEntity.STATE_ULT_SHEATHE;
        if (!this.boss.isAlive() || ultRunning) return false;
        if (this.target != null && this.target.isAlive()) return true;
        Player nearest = this.boss.findNearestPlayerInFollowRange();
        if (nearest != null) {
            this.target = nearest;
            this.boss.setTarget(nearest);
            return true;
        }
        return false;
    }

    @Override
    public void start() {
        this.cooldown = SKILL_COOLDOWN_TICKS;
        this.lockTicks = 0;
        this.skillQueued = false;
    }

    @Override
    public void tick() {
        Player nearest = this.boss.findNearestPlayerInFollowRange();
        if (nearest != null) {
            this.target = nearest;
            this.boss.setTarget(nearest);
        }
        if (this.target == null || !this.target.isAlive()) return;

        // -- 当前技能仍在执行时不排入新技能，避免打断 10 秒魔法飞弹弹幕 --
        if (this.boss.getAttackState() != FinaleEndsustainEntity.STATE_IDLE) {
            this.boss.lookAtPos(this.target.getX(), this.target.getEyeY(), this.target.getZ());
            this.boss.getNavigation().stop();
            return;
        }

        // -- 先 check 是否有技能排队待释放（等 lockTicks 跑完） --
        if (skillQueued) {
            // 锁定期间持续面朝 + 冻结玩家位置
            this.boss.lockOntoPlayer(this.target);
            if (lockTicks > 0) {
                lockTicks--;
                return;
            }
            // 到时间：同一个已选技能同时释放多次；魅惑为持续控制技，只触发一次
            if (queuedCharm) {
                this.boss.beginCharm(this.target);
            } else {
                int castCount = FinaleEndsustainEntity.SPELL_BLACK_HOLE.equals(queuedIronSpell)
                        ? 1 : SIMULTANEOUS_SKILL_CASTS;
                for (int i = 0; i < castCount; i++) {
                    if (queuedBlade) {
                        this.boss.throwEndsustainBlade(this.target);
                    } else if (queuedIronSpell != null) {
                        this.boss.castIronSpell(this.target, queuedIronSpell);
                    } else if (queuedCrystal != null) {
                        this.boss.castGoetyCrystal(this.target, queuedCrystal);
                    }
                }
            }
            skillQueued = false;
            queuedBlade = false;
            queuedCharm = false;
            queuedIronSpell = null;
            queuedCrystal = null;
            return;
        }

        // -- 冷却未到则不走路；目标过远时用瞬移代替追击 --
        if (cooldown > 0) {
            cooldown--;
            this.boss.getNavigation().stop();
            this.boss.lookAtPos(this.target.getX(), this.target.getEyeY(), this.target.getZ());
            double dist = this.boss.distanceToSqr(this.target);
            if (dist > 14.0D * 14.0D) {
                this.boss.teleportNearTarget(this.target, 8.0D);
            }
            return;
        }

        // ==================== 决策技能 ====================
        this.boss.getNavigation().stop();
        cooldown = SKILL_COOLDOWN_TICKS;
        double dist = this.boss.distanceToSqr(this.target);

        if (this.boss.getHealth() < this.boss.getMaxHealth() * 0.50F
                && this.boss.canBeginCharmOrUltimate()
                && this.boss.isCharmReadyFor(this.target)) {
            queuedCharm = true;
            queuedBlade = false;
            queuedIronSpell = null;
            queuedCrystal = null;
            skillQueued = true;
            lockTicks = 1;
        } else if (dist > 14.0D * 14.0D) {
            // 远距离：铁魔法
            queuedIronSpell = this.boss.pickIronSpell();
            queuedBlade = false;
            queuedCharm = false;
            queuedCrystal = null;
            skillQueued = true;
            lockTicks = 1;
        } else if (dist < 7.0D * 7.0D) {
            // 近距离：终焉之刃
            queuedBlade = true;
            queuedCharm = false;
            queuedIronSpell = null;
            queuedCrystal = null;
            skillQueued = true;
            lockTicks = 1;
        } else {
            // 中距离：诡厄巫法
            queuedCrystal = this.boss.pickGoetyCrystal();
            queuedBlade = false;
            queuedCharm = false;
            queuedIronSpell = null;
            skillQueued = true;
            lockTicks = 1;
        }
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }
}
