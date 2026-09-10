package com.endsustain.combat;

import com.endsustain.EndSustain;
import net.minecraft.world.entity.LivingEntity;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

/**
 * Agent 保险层桥接（游戏侧）：
 * 当强杀链所有路径结束后实体仍然存活时，生成带 Agent-Class 清单的临时 jar，
 * 并启动独立进程 attach 到当前游戏 JVM，由 KillAgent 反射移除实体并改写其字节码。
 */
public final class AgentBridge {
    private static volatile Path agentJar;
    /** 已启动、等待放行的 agent（uuid 集合）。 */
    private static final java.util.Set<String> ARMED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AgentBridge() {
    }

    /** 启动外部 agent 保险层（异步）。dump/破壳输出重定向到游戏 logs/endsustain-agent.log。 */
    public static void forceKillViaAgent(LivingEntity target) {
        try {
            Path jar = ensureAgentJar();
            String pid = java.lang.management.ManagementFactory.getRuntimeMXBean().getName();
            int at = pid.indexOf('@');
            if (at > 0) {
                pid = pid.substring(0, at);
            }
            String javaBin = System.getProperty("java.home") + "/bin/java";
            String internalName = target.getClass().getName();
            String uuid = target.getUUID().toString();
            // args = internalName|uuid：agent 侧等待放行属性后才执行
            ProcessBuilder builder = new ProcessBuilder(javaBin, "-cp", jar.toString(),
                    "com.endsustain.agent.Attacher", pid, jar.toString(), internalName + "|" + uuid)
                    .redirectErrorStream(true);
            try {
                // 绝对路径落盘（工作目录漂移时相对路径会静默丢失）
                builder.redirectOutput(ProcessBuilder.Redirect.appendTo(
                        Path.of(System.getProperty("user.dir"), "logs", "endsustain-agent.log").toFile()));
            } catch (Throwable ignored) {
                builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            }
            builder.start();
            ARMED.add(uuid);
            EndSustain.LOGGER.warn("[终焉强杀] Agent 保险层已启动：pid={}，目标类={}，uuid={}",
                    pid, internalName, uuid);
        } catch (Throwable t) {
            EndSustain.LOGGER.error("[终焉强杀] Agent 保险层启动失败", t);
        }
    }

    /**
     * 时序放行（挂在 ServerTickEvent END，在 tickPendingDeaths 之后调用）：
     * mod 输入（高优先级）已在本 tick 执行完毕，放行所有已 attach 的 agent
     * （次优先级输入）——agent 收到信号后在下一 tick 执行四级降级与硬移除。
     */
    public static void releasePendingAgents() {
        for (String uuid : ARMED) {
            System.setProperty("endsustain.agent.go." + uuid, "1");
        }
        ARMED.clear();
    }

    /** 生成临时 agent jar：复制自身 jar 并追加 Agent-Class 清单属性。 */
    private static synchronized Path ensureAgentJar() throws Exception {
        if (agentJar != null && Files.exists(agentJar)) return agentJar;
        Path self = Path.of(AgentBridge.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.putValue("Manifest-Version", "1.0");
        attributes.putValue("Agent-Class", "com.endsustain.agent.KillAgent");
        attributes.putValue("Launcher-Agent-Class", "com.endsustain.agent.KillAgent");
        attributes.putValue("Can-Redefine-Classes", "true");
        attributes.putValue("Can-Retransform-Classes", "true");
        Path temp = Files.createTempFile("endsustain-agent-", ".jar");
        try (JarInputStream in = new JarInputStream(Files.newInputStream(self), false);
             JarOutputStream out = new JarOutputStream(Files.newOutputStream(temp), manifest)) {
            byte[] buffer = new byte[8192];
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                if ("META-INF/MANIFEST.MF".equalsIgnoreCase(entry.getName())) continue;
                out.putNextEntry(new JarEntry(entry.getName()));
                copyStream(in, out, buffer);
                out.closeEntry();
            }
        }
        agentJar = temp;
        return temp;
    }

    private static void copyStream(InputStream in, OutputStream out, byte[] buffer) throws IOException {
        int length;
        while ((length = in.read(buffer)) > 0) {
            out.write(buffer, 0, length);
        }
    }
}
