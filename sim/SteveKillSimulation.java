import java.util.ArrayList;
import java.util.List;

/**
 * 终焉之刃 对 SuperSteve（字符串校验码生命实体）击杀流程模拟。
 *
 * 防御模型（《通用Mixin无效化器报告》）：
 * - 生命存储：字符串校验码数据项 = 前缀 + UUID 哈希十进制子串 + 偏移编码体（每字符 ∈ [123,132]，每字符编码一位数字）
 * - 玩家反伤分支：对「玩家实体来源」的伤害触发反伤并吞掉伤害；无实体来源（genericKill/fellOutOfWorld）不触发
 * - EXIT 死亡演出：SS_HEALTH 合法 0 编码 → 演出 750 tick（37.5 秒）→ idrops 32K 套装完整掉落
 *
 * 强杀链关键：
 * - L0 层 2（字符串校验码生命项归零）：命中 SS_HEALTH——复用原前缀，编码体重写为全 0
 * - 伤害泵用无实体来源（fellOutOfWorld）避开反伤分支
 * - 超时 800 tick > 750 演出，自然完成，agent 不触发
 */
public final class SteveKillSimulation {

    static final class SuperSteveModel {
        private static final char ZERO = (char) 123; // 数字 0 的偏移编码

        String prefix = "SSV";
        String hashToken = String.valueOf(java.util.UUID.randomUUID().hashCode());
        float health = 500.0F;
        boolean dead = false;
        boolean removed = false;
        boolean dropped = false;
        boolean exitPerformance = false;
        int deathTime = 0;
        int retaliationCount = 0;

        /** 生命编码：前缀 + hashToken + 编码体（每数字 +123） */
        String encodeHealth(float value) {
            StringBuilder body = new StringBuilder();
            String digits = String.valueOf((int) value);
            for (char c : digits.toCharArray()) {
                body.append((char) (123 + (c - '0')));
            }
            return prefix + hashToken + body;
        }

        float decodeHealth() {
            String ss = encodeHealth(0); // 占位，用当前编码体
            return health;               // 模拟：内部直接维护数值
        }

        String currentEncoding() {
            return encodeHealth(health);
        }

        float getHealth() { return health; }
        boolean isDeadOrDying() { return health <= 0.0F; }

        boolean hurt(String source, float amount) {
            if (source.startsWith("player")) {
                // 玩家实体来源 → 反伤分支：伤害被吞并反击
                retaliationCount++;
                return false;
            }
            // 无实体来源 → 正常伤害写入
            health = Math.max(0.0F, health - amount);
            if (isDeadOrDying() && !exitPerformance) {
                exitPerformance = true;
                deathTime = 0;
            }
            return true;
        }

        /** 层 2 字符串校验码归零：复用前缀，编码体重写为 0 */
        void zeroStringCodedHealth() {
            if (isDeadOrDying()) return;
            health = 0.0F;
            if (!exitPerformance) {
                exitPerformance = true;
                deathTime = 0;
            }
        }

        void tick() {
            if (!exitPerformance) return;
            deathTime++;
            if (deathTime >= 750 && !dropped) {
                dropped = true;
                dead = true;
                removed = true;  // idrops 32K 套装 + 完整掉落
            }
        }

        void die() {
            // EXIT 演出未完成时 die 无效
            if (exitPerformance && deathTime >= 750) {
                dropped = true;
                dead = true;
                removed = true;
            }
        }
    }

    public static void main(String[] args) {
        SuperSteveModel target = new SuperSteveModel();
        List<String> log = new ArrayList<>();
        int ticks = 0;
        boolean agentUsed = false;

        System.out.println("=== 终焉之刃 → SuperSteve 击杀流程模拟 ===");
        System.out.println("初始：SS_HEALTH 编码=" + target.currentEncoding() + "（生命 500）");
        System.out.println();

        log.add("t+0  入口：溢出近战命中 → forceKill → terminate");
        log.add("t+0  L0 neutralize 层1（数值项）：SS_HEALTH 是字符串，数值匹配未命中（预期）");
        log.add("t+0  L0 neutralize 层2（字符串校验码）：命中——含哈希子串 + [123,132] 编码体");
        target.zeroStringCodedHealth();
        log.add("t+0  层2 结果：编码体重写为 0 → getHealth=" + target.getHealth()
                + "，EXIT 演出启动=" + target.exitPerformance);
        boolean l1 = target.hurt("playerAttack", Float.MAX_VALUE);
        log.add("t+0  L1 hurt(playerAttack, MAX)：玩家来源触发反伤分支，伤害被吞（反伤计数="
                + target.retaliationCount + "，生效=" + l1 + "）");
        boolean l2 = target.hurt("genericKill", Float.MAX_VALUE);
        log.add("t+0  L2 hurt(genericKill, MAX)：无实体来源，不触发反伤（生效=" + l2 + "）");
        log.add("t+0  L3 die：EXIT 演出未完成，无效（预期）");
        target.die();
        log.add("t+0  L4 看门狗队列（800 tick）");

        for (int t = 1; t <= 900; t++) {
            ticks = t;
            if (target.removed) break;
            if (!target.dead) {
                // 伤害泵：无实体来源（避开反伤）
                if (!target.isDeadOrDying()) {
                    target.hurt("fellOutOfWorld", 1.0F);
                }
                // retryTick 每 tick 字符串编码体归零（防写回）
                target.zeroStringCodedHealth();
                target.die();
            }
            target.tick();
            if (t == 750 && target.exitPerformance) {
                log.add("t+750 EXIT 死亡演出完成 → idrops 32K 套装完整掉落");
            }
            if (t == 800 && !target.removed) {
                log.add("t+800 超时兜底（本场景不应触发）");
                if (!target.removed) {
                    agentUsed = true;
                    target.removed = true;
                    log.add("t+800 Agent 保险层：反射移除 + 改写 tick/die 字节码");
                }
            }
        }

        System.out.println("=== 关键节点日志 ===");
        for (String line : log) System.out.println(line);
        System.out.println();
        System.out.println("=== 最终结果 ===");
        System.out.println("t=" + ticks + "，removed=" + target.removed
                + "，完整掉落(32K套装)=" + target.dropped
                + "，玩家反伤次数=" + target.retaliationCount
                + "，agent 保险层=" + agentUsed);
        System.out.println("结论：" + (target.dropped && !agentUsed
                ? "按设计完成：字符串编码归零驱动 EXIT 演出，750t 完整掉落，反伤被无实体来源泵规避"
                : "存在偏差"));
    }
}
