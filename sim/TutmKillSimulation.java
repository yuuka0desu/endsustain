import java.util.ArrayList;
import java.util.List;

/**
 * 终焉之刃 对 tutm（the_ultimate_time_manager）击杀流程模拟。
 *
 * 防御模型（《通用Mixin无效化器报告》）：
 * - 生命存储：persistentData 虚拟生命 Float 键 + 数据项镜像（两处均被 L0 归零命中）
 * - 石碑免伤：64 格内有 ChronosMonolith 时 hurt 泵永远被吞
 * - 死亡序列：health<=0 且「伤害事件通过」才启动；超新星保底一次后第二次进入死亡动画
 * - 时停：Boss 自身免疫，看门狗不受影响
 * - 完整时序：~10s（已用保底）/ ~21s（含保底）
 *
 * 对比场景：
 * A) 石碑存在（hurt 全吞）→ 数据项归零但死亡序列不启动 → 800t 超时 → agent 保险层强制移除（无掉落）
 * B) 无石碑（hurt 通过）→ 伤害泵驱动死亡序列 → 超新星保底 + 死亡动画 → 完整掉落（420t 内完成）
 */
public final class TutmKillSimulation {

    static final class TutmModel {
        float virtualHealth = 400.0F;     // persistentData 浮点键
        float mirrorHealth = 400.0F;      // 数据项镜像
        boolean monolithNearby;           // 石碑免伤开关
        boolean deathSequenceStarted = false;
        boolean supernovaUsed = false;
        int sequenceTick = 0;
        boolean dead = false;
        boolean removed = false;
        boolean dropped = false;

        TutmModel(boolean monolithNearby) { this.monolithNearby = monolithNearby; }

        boolean isDeadOrDying() { return virtualHealth <= 0.0F || mirrorHealth <= 0.0F; }
        float getHealth() { return mirrorHealth; }

        boolean hurt(String source, float amount) {
            if (monolithNearby) return false;   // 石碑免伤：一切 hurt 被吞
            virtualHealth = Math.max(0.0F, virtualHealth - amount);
            if (isDeadOrDying() && !deathSequenceStarted) {
                deathSequenceStarted = true;    // 生命归零 + 伤害事件通过 → 启动死亡序列
                sequenceTick = 0;
            }
            return true;
        }

        /** L0 四层归零（模拟 neutralize 命中） */
        void neutralizeZero() {
            virtualHealth = 0.0F;   // persistentData 浮点键归零
            mirrorHealth = 0.0F;    // 数据项镜像归零
        }

        /** 实体自身 tick：回滚/序列推进 */
        void tick() {
            if (!deathSequenceStarted) {
                // 防御回滚：tutm 每 tick 把虚拟生命写回
                if (mirrorHealth <= 0) {
                    virtualHealth = 400.0F;
                    mirrorHealth = 400.0F;
                }
                return;
            }
            sequenceTick++;
            if (sequenceTick >= 220 && !supernovaUsed) {
                supernovaUsed = true;   // 超新星保底：吸收致死伤害并播动画
                deathSequenceStarted = false; // 保底后需第二次死亡序列
                sequenceTick = 0;
                virtualHealth = 1.0F;   // 保底后残留 1 血（等待第二次归零+伤害）
                mirrorHealth = 1.0F;
            }
            if (sequenceTick >= 220 && supernovaUsed && !dropped) {
                // 第二次死亡动画完成
                dropped = true;
                dead = true;
                removed = true;
            }
        }

        void die() {
            // die 直呼：tutm 死亡序列未完成时无效
            if (deathSequenceStarted && sequenceTick >= 220 && supernovaUsed) {
                dropped = true; dead = true; removed = true;
            }
        }
    }

    static void simulate(boolean monolithNearby) {
        TutmModel target = new TutmModel(monolithNearby);
        List<String> log = new ArrayList<>();
        int ticks = 0;
        log.add("t+0  入口：溢出近战命中 → forceKill → terminate");
        log.add("t+0  L0 neutralize：persistentData 浮点键→0，数据项镜像→0（命中 tutm 双存储）");
        target.neutralizeZero();
        log.add("t+0  L1/L2 伤害管道：" + (monolithNearby ? "被石碑免伤吞掉" : "通过"));
        target.hurt("playerAttack", Float.MAX_VALUE);
        log.add("t+0  L3 die：死亡序列未完成，无效（预期）");
        target.die();
        log.add("t+0  L4 看门狗队列（800 tick）");

        boolean agentUsed = false;
        for (int t = 1; t <= 900; t++) {
            ticks = t;
            if (target.removed) break;
            // 看门狗（按真实 tickPendingDeaths 顺序：伤害泵 → retryTick 归零 → die）
            if (!target.dead) {
                if (!target.isDeadOrDying()) {
                    target.hurt("fellOutOfWorld", 1.0F);  // 伤害泵
                }
                target.neutralizeZero();                  // retryTick 每 tick 归零
                target.die();                             // retryTick die
            }
            target.tick();
            if (t == 800 && !target.removed) {
                log.add("t+800 超时：setRemoved(KILLED) 兜底" + (target.removed ? "" : "被拒"));
                if (!target.removed) {
                    agentUsed = true;
                    target.removed = true;  // agent 改写 tick 字节码 → 强制移除
                    log.add("t+800 Agent 保险层：反射移除 + 改写 tick/die 字节码 → 实体被强制移除");
                }
            }
        }

        System.out.println();
        System.out.println("=== 场景 " + (monolithNearby ? "A：石碑存在（hurt 全吞）" : "B：无石碑（hurt 通过）") + " ===");
        for (String line : log) System.out.println(line);
        System.out.println("最终：t=" + ticks + "，removed=" + target.removed
                + "，完整掉落=" + target.dropped + "，agent 保险层=" + agentUsed);
    }

    public static void main(String[] args) {
        System.out.println("=== 终焉之刃 → tutm（时间管理者）击杀流程模拟 ===");
        System.out.println("初始：虚拟生命(persistentData)=400，镜像数据项=400，死亡序列未启动");
        simulate(true);
        simulate(false);
    }
}
