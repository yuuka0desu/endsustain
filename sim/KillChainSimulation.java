import java.util.ArrayList;
import java.util.List;

/**
 * 终焉之刃 对 亚波伦（goety_revelation:summon_apollyon）伤害链代码级模拟。
 *
 * 依据：
 * - 强杀链真实代码（EndsustainTerminal/TrueKillUtil/EntityHealthStateBridge/AgentBridge）
 * - 《亚波伦无协议完整击杀路径.md》的防御模型
 *
 * 目的：在不启动游戏的情况下逐 tick 推演整条链的行为、预期结果与掉落完整度。
 */
public final class KillChainSimulation {

    // ==================== 亚波伦防御模型（文档还原） ====================
    static final class ApollyonModel {
        float apollyonHealth = 800.0F;   // APOLLYON_HEALTH 数据项（getHealth 读它）
        float maxHealth = 800.0F;
        int deathTime = 0;               // deathTime 状态机
        long leftTime = -1;              // DeathPerformance leftTime
        boolean dead = false;            // vanilla dead 标志
        boolean removed = false;
        boolean dropped = false;         // m_7625_ 掉落已发
        boolean barrierKilled = false;   // Byte FLAGS 位（屏障击杀）
        boolean revived = false;         // Byte FLAGS 位（复活）
        int hurtCooldown = 0;            // setHealth 30t 冷却
        boolean hurtHeadCancelled = true;// hurt HEAD 取消（标题 12/Doom 判定）

        /** vanilla isDeadOrDying = getHealth() <= 0 */
        boolean isDeadOrDying() { return apollyonHealth <= 0.0F; }

        /** getHealth：APOLLYON_HEALTH 下界中直接读 */
        float getHealth() { return apollyonHealth; }

        /** setHealth 重定向：限幅 20 + 冷却 + 回滚（模拟为无效化写回） */
        void setHealth(float value) {
            float capped = Math.max(0.0F, Math.min(20.0F, value));
            if (hurtCooldown > 0) {
                // 冷却期内仅回滚到当前值
                return;
            }
            // Doom/Genesis 回滚：生命不低于 20（模拟"最多磨血"）
            apollyonHealth = Math.max(capped, apollyonHealth <= 0 ? 0 : apollyonHealth);
        }

        /** hurt 管道：HEAD 取消（Doom 判定），返回是否生效 */
        boolean hurt(String sourceType, float amount) {
            if (hurtHeadCancelled) return false;
            apollyonHealth = Math.max(0.0F, apollyonHealth - amount);
            return true;
        }

        /** tickDeath 状态机（ApollyonMixin 注入）：deathTime>=200 触地自行 die */
        void tick() {
            if (!isDeadOrDying()) return;
            if (deathTime == 0) leftTime = 560;
            if (deathTime >= 3) perform();
            deathTime++;
            if (deathTime >= 200 && !dead) {
                die(); // 触地 → die(deathBlow) → m_7625_ 掉落
            }
        }

        /** DeathPerformance.perform：屏障演出 + 复活判定 */
        void perform() {
            leftTime--;
            if (leftTime <= 0) {
                boolean playerNearby = true;    // 模拟：512 格内有玩家
                if (playerNearby && barrierKilled && !revived) {
                    // 屏障复活分支：deathTime 归零、生命 +7%
                    deathTime = 0;
                    dead = false;
                    apollyonHealth += maxHealth * 0.07F;
                    revived = true;
                }
            }
        }

        /** die 闸门：deathTime > 0 才放行 */
        void die() {
            if (deathTime <= 0) return;   // 闸门拦截
            if (dead) return;             // vanilla 重入保护
            dead = true;
            if (deathTime >= 200 && !dropped) {
                dropped = true;           // m_7625_ 全套掉落
                removed = true;           // setRemoved(KILLED) + 箱子 + 击败状态
            }
        }
    }

    // ==================== 强杀链模型（按真实代码顺序） ====================
    static final class KillChain {
        final ApollyonModel target;
        int ticksElapsed = 0;
        boolean armed = false;
        boolean deathTransactionSeen = false;
        final List<String> log = new ArrayList<>();

        KillChain(ApollyonModel target) { this.target = target; }

        void log(String msg) { log.add("t+" + ticksElapsed + "  " + msg); }

        /** 入口：终焉之刃溢出近战命中（AttackEntityEvent） */
        boolean entry() {
            long damage = 745_700L * (96 * 60); // 96 小时
            if (damage <= Integer.MAX_VALUE) {
                log("溢出伤害未激活（需要 >48 小时），仅走普通伤害");
                return false;
            }
            log("入口 A：溢出近战命中（伤害=" + damage + " > int 上限）→ forceKill");
            return true;
        }

        /** forceKill → terminate */
        void terminate() {
            log("mark + clearDefenses（无敌/无敌帧/吸收清零）");
            // L0 neutralize 四层
            log("L0 neutralize：数值项 APOLLYON_HEALTH(" + target.apollyonHealth
                    + ")→0；上限项(≈maxHealth)→0（死亡锁定）");
            target.apollyonHealth = 0.0F;
            log("L0 neutralize：Byte FLAGS→0（barrierKilled/revived 清零 → 屏障复活失效）");
            target.barrierKilled = false;
            target.revived = false;
            log("L0 结果：getHealth=" + target.getHealth() + "，isDeadOrDying=" + target.isDeadOrDying());

            // L1 hurt(playerAttack, MAX)
            boolean l1 = target.hurt("playerAttack", Float.MAX_VALUE);
            log("L1 hurt(playerAttack, MAX)：被 hurt HEAD 取消吞掉（生效=" + l1 + "）");

            // L2 hurt(genericKill, maxHealth*5+1)
            boolean l2 = target.hurt("genericKill", target.maxHealth * 5 + 1);
            log("L2 hurt(genericKill, 5×maxHealth+1)：被吞（生效=" + l2 + "）");

            // L3 die（死亡事务未建立且死亡演出未推进时）
            if (!deathTransactionSeen && target.getHealth() <= 0.0F && target.deathTime == 0) {
                int before = target.deathTime;
                target.die();
                log("L3 die(source)：deathTime=" + before + " 闸门拦截（die 未执行）");
            }
            log("L4 挂入看门狗队列（800 tick）");
        }

        /** 看门狗每 tick（tickPendingDeaths + retryTick） */
        void watchdogTick() {
            ticksElapsed++;
            if (target.removed) return;

            // 死亡事务已建立 → 移出队列（演出自走）
            if (target.dead) {
                if (!deathTransactionSeen) {
                    deathTransactionSeen = true;
                    log("死亡事务已建立 → 移出队列，演出自走（不再干预）");
                }
                return;
            }

            // 伤害泵：!isDeadOrDying 时每 tick fellOutOfWorld(1.0)
            if (!target.isDeadOrDying()) {
                boolean pumped = target.hurt("fellOutOfWorld", 1.0F);
                if (!pumped) log("伤害泵 hurt(fellOutOfWorld,1.0)：被吞（数据项已归零，不影响状态机）");
            }
            // 每 10t 磨血
            if (ticksElapsed % 10 == 0) {
                target.hurt("playerAttack", 99999.0F);
                log("磨血 hurt(playerAttack,99999)：被吞");
            }
            // retryTick：neutralize 重归零 + die 重试（仅 deathTime==0 时主动启动演出）
            if (target.apollyonHealth > 0) {
                target.apollyonHealth = 0;
                log("retryTick：防御写回被重新归零（死亡锁定）");
            }
            if (!target.dead && target.deathTime == 0) {
                int before = target.deathTime;
                target.die();
                if (before > 0 && target.dead) {
                    log("retryTick die：deathTime=" + before + " > 0 闸门通过 → die 执行");
                }
            }
            if (!target.dead && target.deathTime > 0) {
                // 演出自走中：不主动 die，等待实体自身 deathTime>=200 时自行触发
            }
        }

        /** 超时分支（800 tick） */
        void timeout() {
            if (target.removed) return;
            log("800 tick 超时：setRemoved(KILLED) 硬兜底");
            target.removed = true;
            if (!target.removed) {
                log("setRemoved 被拒 → Agent 保险层启动（反射移除 + 改写 tick/die 字节码）");
                armed = true;
                // agent 改写 tick → 下一 tick 必然 remove(KILLED)
                target.removed = true;
                log("agent 改写生效：下一 tick 实体被强制移除");
            }
        }
    }

    public static void main(String[] args) {
        ApollyonModel apollyon = new ApollyonModel();
        KillChain chain = new KillChain(apollyon);

        System.out.println("=== 终焉之刃 → 亚波伦 伤害链模拟 ===");
        System.out.println("初始：APOLLYON_HEALTH=" + apollyon.apollyonHealth
                + "，maxHealth=" + apollyon.maxHealth + "，deathTime=0，FLAGS 正常");
        System.out.println();

        if (!chain.entry()) return;
        chain.terminate();
        System.out.println();

        // 看门狗逐 tick 推进 + 亚波伦自身 tickDeath
        int maxTicks = 900;
        for (int t = 1; t <= maxTicks; t++) {
            chain.watchdogTick();
            apollyon.tick();
            if (apollyon.removed) {
                chain.log("亚波伦 setRemoved(KILLED)：战利品箱 + 击败状态 + 终曲包");
                break;
            }
            if (t == 800) chain.timeout();
        }

        System.out.println("=== 关键节点日志 ===");
        for (String line : chain.log) System.out.println(line);

        System.out.println();
        System.out.println("=== 最终结果 ===");
        System.out.println("deathTime=" + apollyon.deathTime
                + "，removed=" + apollyon.removed
                + "，dropped(完整掉落)=" + apollyon.dropped
                + "，revived=" + apollyon.revived);
        System.out.println("agent 保险层触发=" + chain.armed + "（预期 false：200t 内自然完成 < 800t 超时）");
        System.out.println("掉落完整度：" + (apollyon.dropped ? "完整（m_7625_ 全套 + 箱子 + 击败状态）" : "丢失"));
        System.out.println("结论：" + (apollyon.dropped && !chain.armed
                ? "伤害链按设计完成：数据项归零驱动演出，无提前移除，无需 agent 兜底"
                : "存在偏差，需检查"));
    }
}
